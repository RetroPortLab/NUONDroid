// NuonDroid native host: a small libretro frontend, just enough for the
// NuanceResurrection core. It owns the GL framebuffer the core renders into,
// blits it to the screen (aspect + optional scanlines), pumps the core's audio
// into an AAudio stream, and feeds it input from the Kotlin side.
//
// Everything here runs on the GLSurfaceView render thread except the audio
// callback (AAudio's thread) and setInput/setOptions (UI thread, atomics).

#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>
#include <EGL/egl.h>
#include <aaudio/AAudio.h>

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "libretro.h"

#define TAG "NuonDroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Must match FB_WIDTH / FB_HEIGHT in upstream src/libretro.cpp: that is the
// size of the picture the core draws into our framebuffer.
constexpr int FB_W = 720;
constexpr int FB_H = 480;

std::string g_systemDir, g_saveDir;
std::string g_lastError;

std::atomic<uint32_t> g_input{0};
std::atomic<int> g_scanlinesPct{0};   // 0..100
std::atomic<int> g_aspectMode{0};     // 0 = 4:3, 1 = stretch to screen, 2 = integer-ish 4:3 (no filtering)
std::atomic<bool> g_paused{false};

retro_hw_render_callback* g_hw = nullptr;
bool g_coreInit = false;
bool g_loaded = false;
bool g_glReady = false;
bool g_haveFrame = false;

GLuint g_fbo = 0, g_tex = 0, g_prog = 0;
GLint g_uScan = -1;
int g_surfW = 1, g_surfH = 1;

// ---------------------------------------------------------------- audio ----

constexpr uint32_t RING_FRAMES = 1u << 15;            // power of two
int16_t g_ring[RING_FRAMES * 2];
std::atomic<uint32_t> g_wr{0}, g_rd{0};               // monotonic frame counters
AAudioStream* g_stream = nullptr;

aaudio_data_callback_result_t audioCb(AAudioStream*, void*, void* out, int32_t numFrames) {
    int16_t* dst = static_cast<int16_t*>(out);
    uint32_t rd = g_rd.load(std::memory_order_relaxed);
    const uint32_t wr = g_wr.load(std::memory_order_acquire);
    uint32_t avail = wr - rd;
    uint32_t n = avail < (uint32_t)numFrames ? avail : (uint32_t)numFrames;
    for (uint32_t i = 0; i < n; ++i) {
        const uint32_t s = ((rd + i) & (RING_FRAMES - 1)) * 2;
        dst[i * 2] = g_ring[s];
        dst[i * 2 + 1] = g_ring[s + 1];
    }
    if (n < (uint32_t)numFrames) memset(dst + n * 2, 0, (numFrames - n) * 2 * sizeof(int16_t));  // underrun: silence
    g_rd.store(rd + n, std::memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void audioStart(int rate) {
    if (g_stream) return;
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, rate);     // AAudio resamples if the device runs at another rate
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setDataCallback(b, audioCb, nullptr);
    if (AAudioStreamBuilder_openStream(b, &g_stream) != AAUDIO_OK) {
        LOGE("AAudio: openStream failed");
        g_stream = nullptr;
    } else {
        AAudioStream_requestStart(g_stream);
        LOGI("AAudio: %d Hz, burst %d", rate, AAudioStream_getFramesPerBurst(g_stream));
    }
    AAudioStreamBuilder_delete(b);
}

void audioStop() {
    if (!g_stream) return;
    AAudioStream_requestStop(g_stream);
    AAudioStream_close(g_stream);
    g_stream = nullptr;
    g_rd.store(0);
    g_wr.store(0);
}

size_t audioBatchCb(const int16_t* data, size_t frames) {
    uint32_t wr = g_wr.load(std::memory_order_relaxed);
    const uint32_t rd = g_rd.load(std::memory_order_acquire);
    const uint32_t space = RING_FRAMES - (wr - rd);
    size_t n = frames < space ? frames : space;       // overflow: drop the newest rather than race the reader
    for (size_t i = 0; i < n; ++i) {
        const uint32_t s = ((wr + (uint32_t)i) & (RING_FRAMES - 1)) * 2;
        g_ring[s] = data[i * 2];
        g_ring[s + 1] = data[i * 2 + 1];
    }
    g_wr.store(wr + (uint32_t)n, std::memory_order_release);
    return frames;
}

// ------------------------------------------------------------ libretro cb --

void logCb(enum retro_log_level level, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int prio = level == RETRO_LOG_ERROR ? ANDROID_LOG_ERROR
             : level == RETRO_LOG_WARN  ? ANDROID_LOG_WARN
             : level == RETRO_LOG_DEBUG ? ANDROID_LOG_DEBUG : ANDROID_LOG_INFO;
    __android_log_vprint(prio, "Nuance", fmt, ap);
    va_end(ap);
}

uintptr_t getFbo() { return g_fbo; }
void* getProc(const char* sym) { return (void*)eglGetProcAddress(sym); }

bool envCb(unsigned cmd, void* data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        static_cast<retro_log_callback*>(data)->log = logCb;
        return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *static_cast<const char**>(data) = g_systemDir.c_str();
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *static_cast<const char**>(data) = g_saveDir.c_str();
        return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        auto* hw = static_cast<retro_hw_render_callback*>(data);
        if (hw->context_type != RETRO_HW_CONTEXT_OPENGLES3 && hw->context_type != RETRO_HW_CONTEXT_OPENGLES2)
            return false;
        hw->get_current_framebuffer = getFbo;
        hw->get_proc_address = getProc;
        g_hw = hw;   // points at the core's own static struct, which outlives us
        return true;
    }
    case RETRO_ENVIRONMENT_SET_MESSAGE: {
        // struct retro_message { const char* msg; unsigned frames; } - msg is first.
        const char* msg = *static_cast<const char**>(data);
        if (msg) { g_lastError = msg; LOGE("core message: %s", msg); }
        return true;
    }
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        return true;
    default:
        return false;
    }
}

void videoCb(const void* data, unsigned, unsigned, size_t) {
    if (data == RETRO_HW_FRAME_BUFFER_VALID) g_haveFrame = true;
}

void inputPollCb() {}

int16_t inputStateCb(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || id > 15) return 0;
    return (g_input.load(std::memory_order_relaxed) >> id) & 1;
}

// ------------------------------------------------------------------ GL -----

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("shader compile failed: %s", log);
    }
    return s;
}

void glBuild() {
    static const char* vs =
        "#version 300 es\n"
        "out vec2 uv;\n"
        "void main(){\n"
        "  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n"   // fullscreen triangle
        "  uv = p; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
        "}\n";
    static const char* fs =
        "#version 300 es\n"
        "precision mediump float;\n"
        "in vec2 uv; out vec4 o;\n"
        "uniform sampler2D tex; uniform float scan;\n"
        "void main(){\n"
        "  vec3 c = texture(tex, uv).rgb;\n"
        // The picture is 480 lines but NUON video is effectively 240-line content: darken between line pairs.
        "  float l = abs(fract(uv.y * 240.0) - 0.5) * 2.0;\n"
        "  c *= 1.0 - scan * l * l;\n"
        "  o = vec4(c, 1.0);\n"
        "}\n";
    GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
    g_prog = glCreateProgram();
    glAttachShader(g_prog, v);
    glAttachShader(g_prog, f);
    glLinkProgram(g_prog);
    glDeleteShader(v);
    glDeleteShader(f);
    g_uScan = glGetUniformLocation(g_prog, "scan");

    glGenTextures(1, &g_tex);
    glBindTexture(GL_TEXTURE_2D, g_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, FB_W, FB_H, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &g_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_tex, 0);
    GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (st != GL_FRAMEBUFFER_COMPLETE) LOGE("FBO incomplete: 0x%04x", st);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    g_glReady = true;
}

void blit() {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glViewport(0, 0, g_surfW, g_surfH);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);

    int vx = 0, vy = 0, vw = g_surfW, vh = g_surfH;
    const int mode = g_aspectMode.load();
    if (mode != 1) {
        // Largest 4:3 rectangle that fits.
        if (g_surfW * 3 > g_surfH * 4) { vw = g_surfH * 4 / 3; vx = (g_surfW - vw) / 2; }
        else                           { vh = g_surfW * 3 / 4; vy = (g_surfH - vh) / 2; }
    }
    glViewport(vx, vy, vw, vh);

    glUseProgram(g_prog);
    glUniform1f(g_uScan, g_scanlinesPct.load() / 100.0f);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_tex);
    const GLint f = mode == 2 ? GL_NEAREST : GL_LINEAR;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, f);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, f);
    glBindVertexArray(0);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

}  // namespace

// ------------------------------------------------------------------ JNI ----

extern "C" {

#define JNI(name) Java_com_retroportlab_nuondroid_Native_##name

JNIEXPORT void JNICALL JNI(init)(JNIEnv* env, jobject, jstring systemDir, jstring saveDir, jstring tmpDir) {
    const char* s = env->GetStringUTFChars(systemDir, nullptr);
    const char* v = env->GetStringUTFChars(saveDir, nullptr);
    const char* t = env->GetStringUTFChars(tmpDir, nullptr);
    g_systemDir = s;
    g_saveDir = v;
    // Upstream's archive code makes scratch dirs under $TMPDIR (see patches/).
    setenv("TMPDIR", t, 1);
    env->ReleaseStringUTFChars(systemDir, s);
    env->ReleaseStringUTFChars(saveDir, v);
    env->ReleaseStringUTFChars(tmpDir, t);

    retro_set_environment(envCb);
    retro_set_video_refresh(videoCb);
    retro_set_audio_sample_batch(audioBatchCb);
    retro_set_input_poll(inputPollCb);
    retro_set_input_state(inputStateCb);
    retro_init();
    g_coreInit = true;
}

JNIEXPORT void JNICALL JNI(glCreated)(JNIEnv*, jobject) {
    glBuild();
    // The GL context came back (rotation, surface re-creation): the core must
    // rebuild its textures and shaders too.
    if (g_loaded && g_hw && g_hw->context_reset) g_hw->context_reset();
}

JNIEXPORT void JNICALL JNI(glDestroyed)(JNIEnv*, jobject) {
    if (g_loaded && g_hw && g_hw->context_destroy) g_hw->context_destroy();
    g_glReady = false;
    g_fbo = g_tex = g_prog = 0;   // died with the context
}

JNIEXPORT void JNICALL JNI(glChanged)(JNIEnv*, jobject, jint w, jint h) {
    g_surfW = w > 0 ? w : 1;
    g_surfH = h > 0 ? h : 1;
}

JNIEXPORT jboolean JNICALL JNI(loadGame)(JNIEnv* env, jobject, jstring path) {
    if (!g_coreInit || !g_glReady) return JNI_FALSE;
    g_lastError.clear();
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::string spath = p;
    env->ReleaseStringUTFChars(path, p);

    retro_game_info info;
    memset(&info, 0, sizeof(info));
    info.path = spath.c_str();
    if (!retro_load_game(&info)) {
        if (g_lastError.empty()) g_lastError = "The core could not load this file";
        return JNI_FALSE;
    }
    g_loaded = true;

    retro_system_av_info av;
    memset(&av, 0, sizeof(av));
    retro_get_system_av_info(&av);
    audioStart(av.timing.sample_rate > 0 ? (int)av.timing.sample_rate : 32000);

    // Libretro order: load_game, then the frontend resets the HW context.
    if (g_hw && g_hw->context_reset) g_hw->context_reset();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI(frame)(JNIEnv*, jobject) {
    if (!g_glReady) return;
    if (g_loaded && !g_paused.load()) {
        g_haveFrame = false;
        retro_run();
        // The core leaves arbitrary GL state behind; blit() sets what it needs.
    }
    if (g_haveFrame || g_loaded) blit();
}

JNIEXPORT void JNICALL JNI(setInput)(JNIEnv*, jobject, jint mask) { g_input.store((uint32_t)mask); }

JNIEXPORT void JNICALL JNI(setOptions)(JNIEnv*, jobject, jint scanlinesPct, jint aspectMode) {
    g_scanlinesPct.store(scanlinesPct < 0 ? 0 : scanlinesPct > 100 ? 100 : scanlinesPct);
    g_aspectMode.store(aspectMode);
}

JNIEXPORT void JNICALL JNI(setPaused)(JNIEnv*, jobject, jboolean p) {
    g_paused.store(p);
    if (g_stream) {
        if (p) AAudioStream_requestPause(g_stream);
        else   AAudioStream_requestStart(g_stream);
    }
}

JNIEXPORT void JNICALL JNI(reset)(JNIEnv*, jobject) {
    if (g_loaded) retro_reset();
}

JNIEXPORT void JNICALL JNI(unload)(JNIEnv*, jobject) {
    audioStop();
    if (g_loaded) { retro_unload_game(); g_loaded = false; }
    if (g_coreInit) { retro_deinit(); g_coreInit = false; }
}

JNIEXPORT jstring JNICALL JNI(lastError)(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_lastError.c_str());
}

}  // extern "C"
