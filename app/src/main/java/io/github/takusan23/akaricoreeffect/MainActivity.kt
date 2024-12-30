package io.github.takusan23.akaricoreeffect

import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.takusan23.akaricore.common.toAkariCoreInputOutputData
import io.github.takusan23.akaricore.graphics.AkariGraphicsEffectShader
import io.github.takusan23.akaricore.graphics.AkariGraphicsProcessor
import io.github.takusan23.akaricore.graphics.AkariGraphicsSurfaceTexture
import io.github.takusan23.akaricore.graphics.mediacodec.AkariVideoDecoder
import io.github.takusan23.akaricore.graphics.mediacodec.AkariVideoEncoder
import io.github.takusan23.akaricoreeffect.ui.theme.AkariCoreEffectTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AkariCoreEffectTheme {
                MainScreen()
            }
        }
    }
}

private const val BLUR_FRAGMENT_SHADER = """#version 300 es
precision mediump float;

uniform vec2 vResolution;
uniform sampler2D sVideoFrameTexture;
uniform vec2 direction;

out vec4 FragColor;

// special thanks
// https://github.com/Experience-Monks/glsl-fast-gaussian-blur
vec4 blur(sampler2D image, vec2 uv, vec2 resolution, vec2 direction) {
  vec4 color = vec4(0.0);
  vec2 off1 = vec2(1.411764705882353) * direction;
  vec2 off2 = vec2(3.2941176470588234) * direction;
  vec2 off3 = vec2(5.176470588235294) * direction;
  color += texture(image, uv) * 0.1964825501511404;
  color += texture(image, uv + (off1 / resolution)) * 0.2969069646728344;
  color += texture(image, uv - (off1 / resolution)) * 0.2969069646728344;
  color += texture(image, uv + (off2 / resolution)) * 0.09447039785044732;
  color += texture(image, uv - (off2 / resolution)) * 0.09447039785044732;
  color += texture(image, uv + (off3 / resolution)) * 0.010381362401148057;
  color += texture(image, uv - (off3 / resolution)) * 0.010381362401148057;
  return color;
}

void main() {
  // テクスチャ座標に変換
  vec2 vTextureCoord = gl_FragCoord.xy / vResolution.xy;

  // 出力
  FragColor = blur(sVideoFrameTexture, vTextureCoord, vResolution.xy, direction);
}
"""

@Composable
fun MainScreen() {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videoUri = remember { mutableStateOf<Uri?>(null) }
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { videoUri.value = it }
    )

    fun start() {
        scope.launch(Dispatchers.Default) {
            val result = context.getExternalFilesDir(null)?.resolve("result_${System.currentTimeMillis()}.mp4")!!
            val videoUri = videoUri.value ?: return@launch

            val (width, height) = MediaMetadataRetriever().use { mediaMetadataRetriever ->
                context.contentResolver.openFileDescriptor(videoUri, "r")?.use {
                    mediaMetadataRetriever.setDataSource(it.fileDescriptor)
                }
                val width = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()!!
                val height = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()!!
                width to height
            }

            // OpenGL で描画した内容を録画する動画エンコーダー
            val akariVideoEncoder = AkariVideoEncoder().apply {
                prepare(
                    output = result.toAkariCoreInputOutputData(),
                    containerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                    codecName = MediaFormat.MIMETYPE_VIDEO_HEVC,
                    outputVideoWidth = width,
                    outputVideoHeight = height,
                    keyframeInterval = 1,
                    bitRate = 10_000_000,
                    frameRate = 30,
                    tenBitHdrParametersOrNullSdr = AkariVideoEncoder.TenBitHdrParameters(MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_TRANSFER_HLG)
                )
            }

            // OpenGL で描画する
            val akariGraphicsProcessor = AkariGraphicsProcessor(
                outputSurface = akariVideoEncoder.getInputSurface(),
                width = width,
                height = height,
                isEnableTenBitHdr = true
            ).apply { prepare() }

            // 動画素材のデコーダー
            val akariGraphicsSurfaceTexture = akariGraphicsProcessor.genTextureId { texId -> AkariGraphicsSurfaceTexture(texId) }
            val akariVideoDecoder = AkariVideoDecoder().apply {
                prepare(
                    input = videoUri.toAkariCoreInputOutputData(context),
                    outputSurface = akariGraphicsSurfaceTexture.surface
                )
            }

            // 映像に適用するエフェクト
            // フラグメントシェーダーで記述する
            val akariGraphicsEffectShader = AkariGraphicsEffectShader(
                vertexShaderCode = AkariGraphicsEffectShader.VERTEX_SHADER_GLSL300,
                fragmentShaderCode = BLUR_FRAGMENT_SHADER
            ).apply {
                akariGraphicsProcessor.withOpenGlThread {
                    // コンパイル
                    prepareShader()
                    // direction uniform 変数を追加
                    findVec2UniformLocation("direction")
                }
            }

            try {
                // 描画とエンコードを開始
                coroutineScope {

                    val encoderJob = launch { akariVideoEncoder.start() }

                    val graphicsJob = launch {
                        val loopContinueData = AkariGraphicsProcessor.LoopContinueData(true, 0)
                        val frameMs = 1000 / 30

                        var currentPositionMs = 0L

                        akariGraphicsProcessor.drawLoop {

                            // デコードする
                            val hasNextFrame = akariVideoDecoder.seekTo(currentPositionMs)

                            // 描画する
                            drawSurfaceTexture(akariGraphicsSurfaceTexture)

                            // エフェクトを適用
                            repeat(25) {
                                akariGraphicsEffectShader.setVec2Uniform("direction", 1f, 0f)
                                applyEffect(akariGraphicsEffectShader)
                                akariGraphicsEffectShader.setVec2Uniform("direction", 0f, 1f)
                                applyEffect(akariGraphicsEffectShader)
                            }

                            // ループ継続情報
                            loopContinueData.currentFrameNanoSeconds = currentPositionMs * AkariGraphicsProcessor.LoopContinueData.MILLI_SECONDS_TO_NANO_SECONDS
                            loopContinueData.isRequestNextFrame = hasNextFrame

                            currentPositionMs += frameMs
                            loopContinueData
                        }
                    }

                    // OpenGL メインループを抜けたらエンコーダーも終了
                    graphicsJob.join()
                    encoderJob.cancelAndJoin()
                }
            } finally {
                // 破棄
                akariGraphicsSurfaceTexture.destroy()
                akariGraphicsProcessor.destroy()
                akariVideoDecoder.destroy()

                withContext(Dispatchers.Main) {
                    println("おわり")
                    Toast.makeText(context, "終わり", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {

            Button(onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                Text(text = "動画選択")
            }

            if (videoUri.value != null) {
                Button(onClick = { start() }) {
                    Text(text = "開始")
                }
            }
        }
    }
}