package com.retroportlab.nuondroid

import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.max

/**
 * Turns Android gamepad events into the libretro joypad bitmask the core reads.
 *
 * The core (upstream libretro.cpp, GetInputButtons) maps:
 *   JOYPAD A -> NUON A,  B -> NUON B,  START -> START,  SELECT -> NUON button,
 *   L / R -> L / R shoulders,
 *   X -> C-Up,  Y -> C-Left,  L2 -> C-Down,  R2 -> C-Right.
 *
 * Face buttons go by position on a Retroid / Xbox-style pad: bottom (A) and right (B)
 * are NUON A and B, left (X) is C-Left and top (Y) is C-Up. The right stick drives
 * all four C buttons as a C-pad, and the left stick doubles the d-pad.
 */
class PadState {
    private var keys = 0
    private var axes = 0

    val mask: Int get() = keys or axes

    fun clear() { keys = 0; axes = 0 }

    fun handles(keyCode: Int) = KEY_BITS.containsKey(keyCode)

    fun onKey(keyCode: Int, down: Boolean): Boolean {
        val bit = KEY_BITS[keyCode] ?: return false
        keys = if (down) keys or (1 shl bit) else keys and (1 shl bit).inv()
        return true
    }

    fun onMotion(e: MotionEvent): Boolean {
        var m = 0
        fun set(bit: Int) { m = m or (1 shl bit) }

        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val lx = e.getAxisValue(MotionEvent.AXIS_X)
        val ly = e.getAxisValue(MotionEvent.AXIS_Y)
        val rx = e.getAxisValue(MotionEvent.AXIS_Z)
        val ry = e.getAxisValue(MotionEvent.AXIS_RZ)
        val lt = max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))

        if (hx < -DEAD || lx < -DEAD) set(LEFT)
        if (hx > DEAD || lx > DEAD) set(RIGHT)
        if (hy < -DEAD || ly < -DEAD) set(UP)
        if (hy > DEAD || ly > DEAD) set(DOWN)

        if (ry < -DEAD) set(X)      // C-Up
        if (rx < -DEAD) set(Y)      // C-Left
        if (ry > DEAD) set(L2)      // C-Down
        if (rx > DEAD) set(R2)      // C-Right

        // Handhelds that report triggers as axes instead of key events.
        if (lt > DEAD) set(L2)
        if (rt > DEAD) set(R2)

        axes = m
        return true
    }

    companion object {
        private const val DEAD = 0.5f

        // libretro RETRO_DEVICE_ID_JOYPAD_*
        const val B = 0; const val Y = 1; const val SELECT = 2; const val START = 3
        const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
        const val A = 8; const val X = 9; const val L = 10; const val R = 11
        const val L2 = 12; const val R2 = 13

        private val KEY_BITS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to A,
            KeyEvent.KEYCODE_BUTTON_B to B,
            KeyEvent.KEYCODE_BUTTON_X to Y,      // left face  -> C-Left
            KeyEvent.KEYCODE_BUTTON_Y to X,      // top face   -> C-Up
            KeyEvent.KEYCODE_BUTTON_L1 to L,
            KeyEvent.KEYCODE_BUTTON_R1 to R,
            KeyEvent.KEYCODE_BUTTON_L2 to L2,
            KeyEvent.KEYCODE_BUTTON_R2 to R2,
            KeyEvent.KEYCODE_BUTTON_START to START,
            KeyEvent.KEYCODE_BUTTON_SELECT to SELECT,
            KeyEvent.KEYCODE_DPAD_UP to UP,
            KeyEvent.KEYCODE_DPAD_DOWN to DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to RIGHT,
        )
    }
}
