package net.bastibl.txfile

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import net.bastibl.txfile.databinding.ActivityMainBinding
import java.io.*
import java.util.*
import kotlin.concurrent.thread

private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val usbReceiver = object : BroadcastReceiver() {

        @Suppress("IMPLICIT_CAST_TO_ANY")
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            setupUSB(device)
                        }
                    } else {
                        Log.d("GR", "permission denied for device $device")
                    }
                }
            }
        }
    }

    private fun checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                123)
        } else {
            checkHWPermission()
        }
    }

    private fun checkHWPermission() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList: HashMap<String, UsbDevice> = manager.deviceList
        deviceList.values.forEach { device ->
            if(device.vendorId == 0x2500) {
                val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                registerReceiver(usbReceiver, filter)

                manager.requestPermission(device, permissionIntent)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            123 -> {
                checkHWPermission()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        checkStoragePermission()

        @Suppress("DEPRECATION")
        val external = Environment.getExternalStorageDirectory()
        // val gnuradioDir = File(external.toString() + File.separator + "gnuradio")
        // val volkDir = File(external.toString() + File.separator + "volk")
        // if (!gnuradioDir.exists()) {
        //     Log.d("uhd", "UHD directory does not exist, creating.")
        //     gnuradioDir.mkdirs()
        // }

        val assetManager = assets
        try {
            val files = assetManager.list("files")
            for (f in files!!) {
                Log.d("uhd", "UHD asset file: $f")
                val btn = RadioButton(this);
                btn.text = f;
                binding.radioGroup.addView(btn);
            }
            //for (f in files) {
            //    Log.d("uhd", "Copying file:$f")
            //    val `in` = assetManager.open("uhd" + File.separator + f)
            //    val out: OutputStream = FileOutputStream(uhdDir.toString() + File.separator + f)
            //    copyFile(`in`, out)
            //    `in`.close()
            //    out.flush()
            //    out.close()
            //}
        } catch (e: IOException) {
            Log.e("uhd", "Failed to copy asset file", e)
        }

        // thread(start=true) {
        //     while (!Thread.currentThread().isInterrupted) {
        //         // runOnUiThread {
        //         // }
        //     }
        // }
    }

    @Throws(IOException::class)
    private fun copyFile(`in`: InputStream, out: OutputStream) {
        val buffer = ByteArray(1024)
        var read: Int
        while (`in`.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }
    }

    @SuppressLint("SetTextI18n")
    fun setupUSB(usbDevice: UsbDevice) {

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val connection: UsbDeviceConnection = manager.openDevice(usbDevice)

        val fd = connection.fileDescriptor
        val usbfsPath = usbDevice.deviceName

        val vid = usbDevice.vendorId
        val pid = usbDevice.productId

        Log.d("gnuradio", "#################### NEW RUN ###################")
        Log.d("gnuradio", "Found fd: $fd  usbfs_path: $usbfsPath")
        Log.d("gnuradio", "Found vid: $vid  pid: $pid")

        thread(start = true, priority = Thread.MAX_PRIORITY) {
            fgInit(fd, usbfsPath)
            fgStart(cacheDir.absolutePath)
        }
    }

    override fun onStop() {
        fgStop()
        super.onStop()
    }

    private external fun fgInit(fd: Int, usbfsPath: String): Void
    private external fun fgStart(tmpName: String): Void
    private external fun fgStop(): Void

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
}
