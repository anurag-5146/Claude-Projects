package com.gyrowallpaper

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

abstract class ShaderRenderer {

    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f

    private var program = 0
    private var quadVBO = 0
    private var startTime = System.currentTimeMillis()

    protected var surfaceWidth = 1080
    protected var surfaceHeight = 2400

    // Subclasses provide the fragment shader source
    abstract fun fragmentShader(): String

    private val vertexShader = """
        #version 300 es
        in vec2 aPosition;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """.trimIndent()

    fun onSurfaceCreated() {
        program = buildProgram(vertexShader, fragmentShader())
        quadVBO = createQuadVBO()
        GLES30.glClearColor(0f, 0f, 0f, 1f)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    fun onDrawFrame() {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000f

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Common uniforms
        GLES30.glUniform1f(loc("uTime"), elapsed)
        GLES30.glUniform2f(loc("uResolution"), surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES30.glUniform2f(loc("uTilt"), tiltX, tiltY)

        // Subclass-specific uniforms
        onSetUniforms(program, elapsed)

        // Draw fullscreen quad
        val posLoc = GLES30.glGetAttribLocation(program, "aPosition")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVBO)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    // Override to set additional uniforms
    open fun onSetUniforms(program: Int, time: Float) = Unit

    protected fun loc(name: String) = GLES30.glGetUniformLocation(program, name)

    private fun buildProgram(vert: String, frag: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vert)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, frag)
        return GLES30.glCreateProgram().also { prog ->
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("GyroWallpaper", "Shader error: ${GLES30.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    private fun createQuadVBO(): Int {
        val verts = floatArrayOf(-1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f)
        val buf: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(verts); position(0)
            }
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }
}
