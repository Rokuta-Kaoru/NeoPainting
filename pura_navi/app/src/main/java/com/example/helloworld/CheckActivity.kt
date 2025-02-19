package com.example.helloworld

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.FileNotFoundException
import android.os.Environment
import androidx.compose.ui.unit.Constraints
import java.io.File
import java.io.FileOutputStream

class CheckActivity : ComponentActivity() {

    private val REQUEST_WRITE_STORAGE = 112
    private var processedBitmap: Bitmap? = null // クラスのプロパティとして宣言

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check)

        val imageView: ImageView = findViewById(R.id.imageView) // 画像を表示するImageViewを取得
        val targetAmount = intent.getIntExtra("targetAmount", 0) // 前の画面のtargetAmountを取得

        // ピクセルアート化とCannyエッジ検出の処理
        val pixelSize = when{
            targetAmount in 1..3000 -> Constants.TILE_SIZE_C
            targetAmount in 3001..6000 ->Constants.TILE_SIZE_UC
            targetAmount in 6001..15000 ->Constants.TILE_SIZE_R
            targetAmount in 15001..30000 ->Constants.TILE_SIZE_SR
            targetAmount in 30001..50000 ->Constants.TILE_SIZE_UR
            else -> 0
        }

        // ストレージの書き込み権限を確認
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_STORAGE)
        }

        // OpenCVの初期化
        if (OpenCVLoader.initDebug()) {
            val openCVVersion = org.opencv.core.Core.VERSION
            Toast.makeText(this, "OpenCVVersion:$openCVVersion", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "OpenCVの読み込みに失敗しました", Toast.LENGTH_SHORT).show()
        }

        // インテントから画像URIを取得
        val imageUri = intent.getStringExtra("imageUri")
        if (imageUri != null) {
            try {
                // URIからビットマップを読み込む
                val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(Uri.parse(imageUri)))

                // OpenCV処理の適用
                processedBitmap = processImage(bitmap,pixelSize)
                imageView.setImageBitmap(processedBitmap)

            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }
        } else {
            Toast.makeText(this, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
        }

        // btnOKボタンを取得
        val btnOK: Button = findViewById(R.id.btnOK)
        btnOK.setOnClickListener { // Paintingアクティビティに遷移
            processedBitmap?.let { bitmap ->
                val processedImageUri = saveBitmapToFile(bitmap)
                val intent = Intent(this, PaintingActivity::class.java)
                intent.putExtra("imageUri", processedImageUri.toString())
                intent.putExtra("targetAmount", targetAmount) // 次の画面にtargetAmountを渡す
                startActivity(intent)
            } ?: Toast.makeText(this, "画像が処理されていません", Toast.LENGTH_SHORT).show()
        }

        val btnNO: Button = findViewById(R.id.btnNO)
        btnNO.setOnClickListener {
            finish()
        }
    }

    private fun processImage(bitmap: Bitmap,pixelSize: Int): Bitmap {
        val originalMat = Mat()
        Utils.bitmapToMat(bitmap, originalMat)

        // 固定サイズにリサイズ（元サイズ1024×1024）
        val fixedSize = Size(1024.0, 1024.0)
        val resizedMat = Mat()
        Imgproc.resize(originalMat, resizedMat, fixedSize)

        // グレースケールに変換
        val grayMat = Mat()
        Imgproc.cvtColor(resizedMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        val smallImage = Mat()
        Imgproc.resize(grayMat, smallImage, Size(pixelSize.toDouble(), pixelSize.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        val pixelArtMat = Mat()
        Imgproc.resize(smallImage, pixelArtMat, fixedSize, 0.0, 0.0, Imgproc.INTER_NEAREST)

        val edgesMat = Mat()
        Imgproc.Canny(pixelArtMat, edgesMat, 50.0, 70.0)

        val edgesColorMat = Mat(edgesMat.size(), CvType.CV_8UC3)
        Imgproc.cvtColor(edgesMat, edgesColorMat, Imgproc.COLOR_GRAY2BGR)
        for (row in 0 until edgesColorMat.rows()) {
            for (col in 0 until edgesColorMat.cols()) {
                val pixel = edgesColorMat.get(row, col)
                if (pixel[0] == 255.0) {
                    edgesColorMat.put(row, col, byteArrayOf(0, 0, 255.toByte()))
                }
            }
        }

        val invertedMat = Mat()
        Core.bitwise_not(edgesMat, invertedMat)
        val binaryMat = Mat()
        Imgproc.threshold(invertedMat, binaryMat, 200.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.resize(binaryMat, binaryMat, edgesColorMat.size())

        val binaryColorMat = Mat()
        Imgproc.cvtColor(binaryMat, binaryColorMat, Imgproc.COLOR_GRAY2BGR)
        val resultMat = Mat()
        Core.add(edgesColorMat, binaryColorMat, resultMat)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(10.0, 10.0))
        Imgproc.dilate(edgesColorMat, edgesColorMat, kernel)

        val resultBitmap = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resultMat, resultBitmap)

        // 生成されたビットマップを小さくリサイズ（例として800x800に変更）
        val resizedBitmap = Bitmap.createScaledBitmap(resultBitmap, 800, 800, true)

        return resizedBitmap
    }

    // Bitmapを保存するメソッド
    private fun saveBitmapToFile(bitmap: Bitmap): Uri {
        // 前回の画像ファイルを削除
        val previousFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "processed_image.png")
        if (previousFile.exists()) {
            previousFile.delete()
        }

        // 新たに保存
        val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "processed_image.png")
        val outputStream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        outputStream.flush()
        outputStream.close()

        return Uri.fromFile(file)
    }

}
