package com.github.mrmitew.grabcutsample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.Toast
import com.github.mrmitew.grabcutsample.R.id.top
import com.tbruyelle.rxpermissions2.RxPermissions
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc


typealias Coordinates = Pair<Point, Point>
class MainActivity : AppCompatActivity() {
    companion object {
        val REQUEST_OPEN_IMAGE = 1337

        init {
            System.loadLibrary("opencv_java3")
        }
    }

    private val coordinates: Coordinates = Coordinates(Point(-1.0, -1.0), Point(-1.0, -1.0))
    private val disposables = CompositeDisposable()
    private var currentPhotoPath: String = ""
    private lateinit var rxPermissions: RxPermissions
    private lateinit var bitmap: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        check(OpenCVLoader.initDebug(), { Toast.makeText(this, "OpenCV was not initialized properly", Toast.LENGTH_SHORT).show() })

        rxPermissions = RxPermissions(this)

        image.setOnTouchListener { _, event ->
            if (!isPhotoChosen()) {
                return@setOnTouchListener false
            }
            if (event.action == MotionEvent.ACTION_DOWN) {
                val bounds = getBitmapPositionInsideImageView(image)
                val xScaled = (event.x / bounds[4]) - bounds[0]
                val yScaled = (event.y / bounds[5]) - bounds[1]
                if (!hasChosenTopLeft()) {
                    coordinates.first.apply {
                        x = xScaled.toDouble()
                        y = yScaled.toDouble()
                    }
                } else if (!hasChosenBottomRight()) {
                    with(Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)) {
                        coordinates.second.apply {
                            x = xScaled.toDouble()
                            y = yScaled.toDouble()
                        }
                        val rectPaint = Paint().apply {
                            setARGB(255, 255, 0, 0)
                            style = Paint.Style.STROKE
                            strokeWidth = 3f
                        }
                        Canvas(this).apply {
                            drawBitmap(bitmap, 0f, 0f, null)
                            drawRect(RectF(coordinates.first.x.toFloat(), coordinates.first.y.toFloat(), coordinates.second.x.toFloat(), coordinates.second.y.toFloat()), rectPaint)
                        }
                        image.setImageDrawable(BitmapDrawable(resources, this))
                    }

                } else {
                    resetTarget()
                }
            }
            true
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    private fun decodeBitmapFromFilePath(currentPhotoPath: String): Bitmap {
        val bmOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(currentPhotoPath, bmOptions)
        bmOptions.apply {
            inJustDecodeBounds = false
            inPurgeable = true
        }
        return BitmapFactory.decodeFile(currentPhotoPath, bmOptions)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        when (requestCode) {
            REQUEST_OPEN_IMAGE -> if (resultCode == Activity.RESULT_OK && data.data != null) {
                val imgUri: Uri = data.data
                val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
                val cursor = contentResolver.query(imgUri, filePathColumn, null, null, null)
                // TODO: Provide complex object that has both path and extension
                currentPhotoPath = cursor.moveToFirst()
                        .let { cursor.getString(cursor.getColumnIndex(filePathColumn[0])) }
                        .also { cursor.close() }
                bitmap = decodeBitmapFromFilePath(currentPhotoPath)
                image.setImageBitmap(bitmap)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_target -> {
                check(isPhotoChosen())
                resetTarget()
            }
            R.id.action_open_img -> {
                rxPermissions
                        .request(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        .subscribe({ granted ->
                            if (granted) {
                                val getPictureIntent = Intent(Intent.ACTION_GET_CONTENT)
                                        .apply { type = "image/*" }
                                val pickPictureIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                val chooserIntent = Intent.createChooser(getPictureIntent, "Select Image")
                                        .apply { putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pickPictureIntent)) }
                                startActivityForResult(chooserIntent, REQUEST_OPEN_IMAGE)
                            } else {
                                Toast.makeText(this, "App needs permission to read/write external storage", Toast.LENGTH_SHORT).show()
                            }
                        })
                        .addTo(disposables)
                return true
            }
            R.id.action_cut_image -> {
                if (isPhotoChosen() && isTargetChosen()) {
                    Single.fromCallable { extractForegroundFromBackground(coordinates, currentPhotoPath) }
                            .subscribeOn(Schedulers.computation())
                            .observeOn(AndroidSchedulers.mainThread())
                            .doOnSubscribe { vg_loading.visibility = VISIBLE }
                            .doOnSuccess { displayResult(it) }
                            .doFinally {
                                resetTargetCoordinates()
                                vg_loading.visibility = GONE
                            }
                            .subscribe()
                            .addTo(disposables)
                }
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun resetTarget() {
        resetTargetCoordinates()
        image.setImageBitmap(bitmap)
    }

    private fun resetTargetCoordinates() {
        coordinates.apply {
            first.apply { x = -1.0; y = -1.0 }
            second.apply { x = -1.0; y = -1.0 }
        }
    }

    private fun isPhotoChosen() = currentPhotoPath.isNotBlank()

    private fun isTargetChosen() = coordinates.first.x != -1.0 && coordinates.first.y != -1.0 &&
            coordinates.second.x != -1.0 && coordinates.second.y != -1.0

    private fun hasChosenTopLeft() = coordinates.first.x != -1.0 && coordinates.first.y != -1.0
    private fun hasChosenBottomRight() = coordinates.second.x != -1.0 && coordinates.second.y != -1.0

    private fun displayResult(currentPhotoPath: String) {
        // TODO: Provide complex object that has both path and extension
        image.apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            setImageBitmap(BitmapFactory.decodeFile(currentPhotoPath + "_tmp.jpg"))
            invalidate()
        }
    }

    private fun extractForegroundFromBackground(coordinates: Coordinates, currentPhotoPath: String): String {
        // TODO: Provide complex object that has both path and extension

        val img = Imgcodecs.imread(currentPhotoPath)
        val firstMask = Mat()
        val bgModel = Mat()
        val fgModel = Mat()
        val source = Mat(1, 1, CvType.CV_8U, Scalar(Imgproc.GC_PR_FGD.toDouble()))
        val dst = Mat()
        val rect = Rect(coordinates.first, coordinates.second)
        val vals = Mat(1, 1, CvType.CV_8UC3, Scalar(0.0))

        Imgproc.grabCut(img, firstMask, rect, bgModel, fgModel, 5, Imgproc.GC_INIT_WITH_RECT)
        Core.compare(firstMask, source, firstMask, Core.CMP_EQ)

        val foreground = Mat(img.size(), CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        img.copyTo(foreground, firstMask)

        val color = Scalar(255.0, 0.0, 0.0, 255.0)
        Imgproc.rectangle(img, coordinates.first, coordinates.second, color)

        var background = Mat(img.size(), CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        val tmp = Mat()
        Imgproc.resize(background, tmp, img.size())
        background = tmp
        val mask = Mat(foreground.size(), CvType.CV_8UC1, Scalar(255.0, 255.0, 255.0))

        Imgproc.cvtColor(foreground, mask, Imgproc.COLOR_BGR2GRAY)
        Imgproc.threshold(mask, mask, 254.0, 255.0, Imgproc.THRESH_BINARY_INV)
        background.copyTo(dst)
        background.setTo(vals, mask)
        Core.add(background, foreground, dst, mask)

        // Save to the storage
        Imgcodecs.imwrite(currentPhotoPath + "_tmp.jpg", dst)

        // Clean up resources
        firstMask.release()
        source.release()
        bgModel.release()
        fgModel.release()
        vals.release()
        tmp.release()
        dst.release()

        return currentPhotoPath
    }

    /**
     * @author Glen Pierce
     * @link https://stackoverflow.com/questions/35250485/how-to-translate-scale-ontouchevent-coordinates-onto-bitmap-canvas-in-android-in
     */
    private fun getBitmapPositionInsideImageView(imageView: ImageView): FloatArray {
        val rect = FloatArray(6)

        if (imageView.drawable == null)
            return rect

        // Get image dimensions
        // Get image matrix values and place them in an array
        val f = FloatArray(9)
        imageView.imageMatrix.getValues(f)

        // Extract the scale values using the constants (if aspect ratio maintained, scaleX == scaleY)
        val scaleX = f[Matrix.MSCALE_X]
        val scaleY = f[Matrix.MSCALE_Y]

        rect[4] = scaleX
        rect[5] = scaleY

        // Get the drawable (could also get the bitmap behind the drawable and getWidth/getHeight)
        val d = imageView.drawable
        val origW = d.intrinsicWidth
        val origH = d.intrinsicHeight

        // Calculate the actual dimensions
        val actW = Math.round(origW * scaleX)
        val actH = Math.round(origH * scaleY)

        rect[2] = actW.toFloat()
        rect[3] = actH.toFloat()

        // Get image position
        // We assume that the image is centered into ImageView
        val imgViewW = imageView.width
        val imgViewH = imageView.height

        val left = (imgViewW - actW) / 2
        val top = (imgViewH - actH) / 2

        rect[0] = left.toFloat()
        rect[1] = top.toFloat()

        return rect
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
    }
}
