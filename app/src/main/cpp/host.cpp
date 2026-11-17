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
#include <mutex>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <ctime>
#include <unistd.h>

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
std::atomic<int> g_aspectMode{0};     // 0 = 4:3, 1 = stretch to screen
std::atomic<int> g_filter{0};         // 0 = soft (bilinear), 1 = sharp pixels (sharp-bilinear), 2 = smooth HD (Catmull-Rom)
std::atomic<bool> g_paused{false};

retro_hw_render_callback* g_hw = nullptr;
bool g_coreInit = false;
bool g_loaded = false;
bool g_glReady = false;
bool g_haveFrame = false;

GLuint g_fbo = 0, g_tex = 0, g_prog = 0;

// ---------------------------------------------------------------- VLM -----
// VLM mode (vlmhost.cpp in the core): the player firmware's Virtual Light Machine,
// fed with music the Kotlin side decodes. The core's own audio is unused; the
// music goes straight to AAudio and the last 128 played frames go to VLM.
extern "C" {
extern int nuance_vlm_mode;
void nuance_vlm_set_audio(const int16_t* lr);
void nuance_vlm_set_effect(int n);
int nuance_vlm_effect(void);
int nuance_vlm_effect_count(void);
const uint8_t* nuance_vlm_frame(int* w, int* h, int* stride);
}
bool g_vlm = false;
GLuint g_vlmTex = 0;
int g_vlmW = 180, g_vlmH = 120;
std::atomic<int> g_vlmLuma{0};          // mean luma of the last VLM frame, 0..255
std::atomic<int> g_audioRate{44100};
GLint g_uScan = -1, g_uFilter = -1, g_uTexSize = -1, g_uOutSize = -1;

// ---------------------------------------------------------------- stats ----
// Exported by the core (patches/0007): frames the game itself has flipped to,
// and instruction packets executed by all MPEs.
extern "C" volatile uint32_t nuance_video_flips;
extern "C" uint64_t nuance_packets_executed;
std::atomic<float> g_statFps{0}, g_statGameFps{0}, g_statMops{0};
uint64_t g_statT0 = 0, g_statPackets0 = 0;
uint32_t g_statFrames = 0, g_statFlips0 = 0;

uint64_t nowUs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000u + (uint64_t)ts.tv_nsec / 1000u;
}

// Called once per presented frame on the GL thread; publishes per-second rates.
void statsTick() {
    const uint64_t now = nowUs();
    if (!g_statT0) { g_statT0 = now; g_statFlips0 = nuance_video_flips; g_statPackets0 = nuance_packets_executed; }
    g_statFrames++;
    const uint64_t dt = now - g_statT0;
    if (dt < 1000000) return;
    const double sec = dt / 1e6;
    const uint32_t flips = nuance_video_flips;
    const uint64_t packets = nuance_packets_executed;
    g_statFps.store((float)(g_statFrames / sec));
    g_statGameFps.store((float)((uint32_t)(flips - g_statFlips0) / sec));
    g_statMops.store((float)((packets - g_statPackets0) / sec / 1e6));
    g_statT0 = now; g_statFrames = 0; g_statFlips0 = flips; g_statPackets0 = packets;
}
int g_surfW = 1, g_surfH = 1;

// ---------------------------------------------------------------- audio ----

constexpr uint32_t RING_FRAMES = 1u << 15;            // power of two
int16_t g_ring[RING_FRAMES * 2];
std::atomic<uint32_t> g_wr{0}, g_rd{0};               // monotonic frame counters
AAudioStream* g_stream = nullptr;
std::recursive_mutex g_streamMx;   // stream open/close/pause from the GL, UI and decoder threads

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
    std::lock_guard<std::recursive_mutex> lk(g_streamMx);
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
    std::lock_guard<std::recursive_mutex> lk(g_streamMx);
    if (!g_stream) return;
    AAudioStream_requestStop(g_stream);
    AAudioStream_close(g_stream);
    g_stream = nullptr;
    g_rd.store(0);
    g_wr.store(0);
}

size_t audioBatchCb(const int16_t* data, size_t frames) {
    if (g_vlm) return frames;
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

// Music for VLM mode: returns how many frames were taken (0 = ring full, try later).
// Keeps 4096 frames of history behind the read position for the VLM window.
uint32_t vlmPush(const int16_t* data, uint32_t frames) {
    uint32_t wr = g_wr.load(std::memory_order_relaxed);
    const uint32_t rd = g_rd.load(std::memory_order_acquire);
    const uint32_t used = wr - rd;
    const uint32_t cap = RING_FRAMES - 4096;
    const uint32_t space = used < cap ? cap - used : 0;
    const uint32_t n = frames < space ? frames : space;
    for (uint32_t i = 0; i < n; ++i) {
        const uint32_t s = ((wr + i) & (RING_FRAMES - 1)) * 2;
        g_ring[s] = data[i * 2];
        g_ring[s + 1] = data[i * 2 + 1];
    }
    g_wr.store(wr + n, std::memory_order_release);
    return n;
}

// The 128 frames that just went to the speaker.
void vlmFeedWindow() {
    int16_t win[256];
    const uint32_t rd = g_rd.load(std::memory_order_acquire);
    for (uint32_t i = 0; i < 128; ++i) {
        const uint32_t f = rd - 128 + i;
        const uint32_t s = (f & (RING_FRAMES - 1)) * 2;
        const bool valid = rd >= 128;
        win[i * 2] = valid ? g_ring[s] : 0;
        win[i * 2 + 1] = valid ? g_ring[s + 1] : 0;
    }
    nuance_vlm_set_audio(win);
}

// Latest VLM frame (big-endian 32-bit YCrCb) -> RGBA texture.
void vlmUpload() {
    int w = 0, h = 0, stride = 0;
    const uint8_t* src = nuance_vlm_frame(&w, &h, &stride);
    if (!src || w <= 0 || h <= 0 || w > 360 || h > 240) return;
    static uint8_t rgba[360 * 240 * 4];
    uint64_t lumaSum = 0;
    for (int y = 0; y < h; ++y) {
        const uint8_t* p = src + (size_t)y * stride * 4;
        uint8_t* o = rgba + (size_t)y * w * 4;
        for (int x = 0; x < w; ++x, p += 4, o += 4) {
            const float Y = (p[0] - 16) * (255.0f / 219.0f), Cr = p[1] - 128.0f, Cb = p[2] - 128.0f;
            const float r = Y + 1.402f * Cr, g = Y - 0.344f * Cb - 0.714f * Cr, b = Y + 1.772f * Cb;
            o[0] = (uint8_t)(r < 0 ? 0 : r > 255 ? 255 : r);
            o[1] = (uint8_t)(g < 0 ? 0 : g > 255 ? 255 : g);
            o[2] = (uint8_t)(b < 0 ? 0 : b > 255 ? 255 : b);
            o[3] = 255;
            lumaSum += p[0];
        }
    }
    g_vlmLuma.store((int)(lumaSum / ((uint64_t)w * h)));
    if (!g_vlmTex || w != g_vlmW || h != g_vlmH) {
        if (!g_vlmTex) glGenTextures(1, &g_vlmTex);
        glBindTexture(GL_TEXTURE_2D, g_vlmTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        g_vlmW = w; g_vlmH = h;
    }
    glBindTexture(GL_TEXTURE_2D, g_vlmTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
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
        "precision highp float;\n"
        "in vec2 uv; out vec4 o;\n"
        "uniform sampler2D tex; uniform float scan; uniform int filt;\n"
        "uniform vec2 texSize; uniform vec2 outSize;\n"
        // Catmull-Rom bicubic, 16 taps: smooth upscaling that keeps edges reasonably crisp.
        "vec3 bicubic(vec2 p){\n"
        "  vec2 tc = p * texSize - 0.5; vec2 f = fract(tc); ivec2 b = ivec2(floor(tc));\n"
        "  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));\n"
        "  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);\n"
        "  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));\n"
        "  vec2 w3 = f * f * (-0.5 + 0.5 * f);\n"
        "  float wx[4] = float[4](w0.x, w1.x, w2.x, w3.x); float wy[4] = float[4](w0.y, w1.y, w2.y, w3.y);\n"
        "  ivec2 mx = ivec2(texSize) - 1; vec3 acc = vec3(0.0);\n"
        "  for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++)\n"
        "    acc += texelFetch(tex, clamp(b + ivec2(i - 1, j - 1), ivec2(0), mx), 0).rgb * (wx[i] * wy[j]);\n"
        "  return clamp(acc, 0.0, 1.0);\n"
        "}\n"
        // Sharp-bilinear: nearest-neighbour inside each source pixel, a one-output-pixel
        // linear blend at its edges - crisp pixels of even size at any scale, no shimmer.
        "vec2 sharpUv(vec2 p){\n"
        "  vec2 t = p * texSize; vec2 scale = max(outSize / texSize, vec2(1.0));\n"
        "  vec2 c = fract(t) - 0.5; vec2 r = 0.5 - 0.5 / scale;\n"
        "  return (floor(t) + (c - clamp(c, -r, r)) * scale + 0.5) / texSize;\n"
        "}\n"
        "void main(){\n"
        "  vec3 c = filt == 2 ? bicubic(uv) : texture(tex, filt == 1 ? sharpUv(uv) : uv).rgb;\n"
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
    g_uFilter = glGetUniformLocation(g_prog, "filt");
    g_uTexSize = glGetUniformLocation(g_prog, "texSize");
    g_uOutSize = glGetUniformLocation(g_prog, "outSize");

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
    if (mode == 0) {
        // Largest 4:3 rectangle that fits.
        if (g_surfW * 3 > g_surfH * 4) { vw = g_surfH * 4 / 3; vx = (g_surfW - vw) / 2; }
        else                           { vh = g_surfW * 3 / 4; vy = (g_surfH - vh) / 2; }
    }
    glViewport(vx, vy, vw, vh);

    glUseProgram(g_prog);
    glUniform1f(g_uScan, g_scanlinesPct.load() / 100.0f);
    glUniform1i(g_uFilter, g_filter.load());
    const bool vlm = g_vlm && g_vlmTex;
    glUniform2f(g_uTexSize, vlm ? (float)g_vlmW : (float)FB_W, vlm ? (float)g_vlmH : (float)FB_H);
    glUniform2f(g_uOutSize, (float)vw, (float)vh);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, vlm ? g_vlmTex : g_tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
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
    g_fbo = g_tex = g_prog = g_vlmTex = 0;   // died with the context
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
    audioStart(g_vlm ? g_audioRate.load() : av.timing.sample_rate > 0 ? (int)av.timing.sample_rate : 32000);

    // Libretro order: load_game, then the frontend resets the HW context.
    if (g_hw && g_hw->context_reset) g_hw->context_reset();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI(frame)(JNIEnv*, jobject) {
    if (!g_glReady) return;
    if (g_loaded && !g_paused.load()) {
        g_haveFrame = false;
        if (g_vlm) vlmFeedWindow();
        retro_run();
        if (g_vlm) vlmUpload();
        // The core leaves arbitrary GL state behind; blit() sets what it needs.
    }
    if (g_haveFrame || g_loaded) blit();
    if (g_loaded && !g_paused.load()) statsTick();
    else g_statT0 = 0;   // start a fresh one-second window after a pause
    // Swap interval is 0 (see EmulatorActivity), so nothing else slows this loop
    // down while the core isn't running: don't spin redrawing the paused frame.
    if (g_paused.load()) usleep(16000);
}

JNIEXPORT void JNICALL JNI(setInput)(JNIEnv*, jobject, jint mask) { g_input.store((uint32_t)mask); }

JNIEXPORT void JNICALL JNI(setOptions)(JNIEnv*, jobject, jint scanlinesPct, jint aspectMode, jint filter) {
    g_scanlinesPct.store(scanlinesPct < 0 ? 0 : scanlinesPct > 100 ? 100 : scanlinesPct);
    g_aspectMode.store(aspectMode);
    g_filter.store(filter < 0 || filter > 2 ? 0 : filter);
}

// [screen fps, game fps, NUON instruction packets per second in millions]
JNIEXPORT jfloatArray JNICALL JNI(getStats)(JNIEnv* env, jobject) {
    const jfloat v[3] = { g_statFps.load(), g_statGameFps.load(), g_statMops.load() };
    jfloatArray a = env->NewFloatArray(3);
    if (a) env->SetFloatArrayRegion(a, 0, 3, v);
    return a;
}

JNIEXPORT void JNICALL JNI(setPaused)(JNIEnv*, jobject, jboolean p) {
    g_paused.store(p);
    std::lock_guard<std::recursive_mutex> lk(g_streamMx);
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

// ------------------------------------------------------------- VLM mode --
// Before loadGame (with the player firmware's pe as the "game").
JNIEXPORT void JNICALL JNI(vlmEnable)(JNIEnv*, jobject) { g_vlm = true; nuance_vlm_mode = 1; }

// Any thread. Interleaved stereo 16-bit; returns frames taken (0 = full, retry shortly).
JNIEXPORT jint JNICALL JNI(vlmPush)(JNIEnv* env, jobject, jshortArray pcm, jint offsetFrames, jint frames) {
    jshort* p = env->GetShortArrayElements(pcm, nullptr);
    const uint32_t n = vlmPush(p + (size_t)offsetFrames * 2, (uint32_t)frames);
    env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
    return (jint)n;
}

// Any thread: frames queued but not yet played; frames played since the last rate change.
JNIEXPORT jint JNICALL JNI(vlmQueued)(JNIEnv*, jobject) { return (jint)(g_wr.load() - g_rd.load()); }

// Decoder thread (the only one pushing): new sample rate for the next track. Drops what's queued.
JNIEXPORT void JNICALL JNI(vlmSetRate)(JNIEnv*, jobject, jint rate) {
    std::lock_guard<std::recursive_mutex> lk(g_streamMx);
    if (rate <= 0 || rate == g_audioRate.load()) return;
    g_audioRate.store(rate);
    if (g_stream) { audioStop(); audioStart(rate); if (g_paused.load()) AAudioStream_requestPause(g_stream); }
}

// Decoder thread: drop queued music (skipping tracks).
JNIEXPORT void JNICALL JNI(vlmFlush)(JNIEnv*, jobject) {
    std::lock_guard<std::recursive_mutex> lk(g_streamMx);
    if (g_stream) { audioStop(); audioStart(g_audioRate.load()); if (g_paused.load()) AAudioStream_requestPause(g_stream); }
}

JNIEXPORT void JNICALL JNI(vlmSetEffect)(JNIEnv*, jobject, jint n) { nuance_vlm_set_effect(n); }
JNIEXPORT jint JNICALL JNI(vlmEffect)(JNIEnv*, jobject) { return nuance_vlm_effect(); }
JNIEXPORT jint JNICALL JNI(vlmEffectCount)(JNIEnv*, jobject) { return nuance_vlm_effect_count(); }
JNIEXPORT jint JNICALL JNI(vlmLuma)(JNIEnv*, jobject) { return g_vlmLuma.load(); }

}  // extern "C"
