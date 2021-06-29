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

    private var haveStoragePermission : Boolean = false
    private var haveHWPermission : Boolean = false
    private lateinit var binding: ActivityMainBinding

    private fun checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                123)
        } else {
            haveStoragePermission = true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            123 -> {
                haveStoragePermission = true
            }
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

    private val usbReceiver = object : BroadcastReceiver() {
        @Suppress("IMPLICIT_CAST_TO_ANY")
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            haveHWPermission = true
                            setupUSB(device)
                        }
                    } else {
                        Log.d("GR", "permission denied for device $device")
                    }
                }
            }
        }
    }

    private var thread : Thread? = null;

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        checkStoragePermission()
        checkHWPermission()

        var files = assets.list("files")
        if (files.isNullOrEmpty()) {
            binding.textView.text = "no asset files found"
            return
        }
        for (f in files!!) {
            Log.d("uhd", "UHD asset file: $f")
            val btn = RadioButton(this);
            btn.text = f;
            binding.radioGroup.addView(btn);
        }
        binding.radioGroup.check(1);

        binding.buttonSend.setOnClickListener {
            if(!haveStoragePermission) {
                binding.textView.text = "no storage permssion"
                return@setOnClickListener
            }
            //if(!haveHWPermission) {
            //    binding.textView.text = "no HW permssion"
            //    return@setOnClickListener
            //}

            thread?.let {
                if(thread?.isAlive == true) {
                    binding.textView.text = "flowgraph is running"
                    return@setOnClickListener
                }
            }

            binding.textView.text = "starting flowgraph"


            @Suppress("DEPRECATION")
            val external = Environment.getExternalStorageDirectory()

            val grDir = File(external.toString() + File.separator + "gnuradio");
            val volkDir = File(external.toString() + File.separator + "volk");
            if (!grDir.exists()) {
                Log.d("gr", "GNU Radio directory doesn't exist, creating.")
                grDir.mkdirs()
            }
            if (!volkDir.exists()) {
                Log.d("gr", "Volk directory doesn't exist, creating.")
                volkDir.mkdirs()
            }

            try {
                files = assets.list("gnuradio")
                for (f in files!!) {
                    val outFile = File(grDir.toString() + File.separator + f)
                    if (outFile.exists()) {
                        Log.d("gr", "Skipping file:$f")
                        continue
                    }

                    Log.d("gr", "Copying file:$f")
                    val inStream = assets.open("gnuradio" + File.separator + f)
                    val out: OutputStream = FileOutputStream(grDir.toString() + File.separator + f)
                    copyFile(inStream, out)
                    inStream.close()
                    out.flush()
                    out.close()
                }
            } catch (e: IOException) {
                Log.e("gr", "Failed to copy asset file", e)
            }

            thread = thread(start = true, priority = Thread.MAX_PRIORITY) {
                fgInit(usbConnection?.fileDescriptor ?: 0, usbPath ?: "")
                fgStart("foo")
            }
        }

        binding.buttonStop.setOnClickListener {
            thread?.let {
                if(thread?.isAlive == true) {
                    binding.textView.text = "flowgraph is running"
                    fgStop();
                    thread?.join();
                    return@setOnClickListener
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(`in`: InputStream, out: OutputStream) {
        val buffer = ByteArray(1024)
        var read: Int
        while (`in`.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }
    }

    private var usbConnection : UsbDeviceConnection? = null;
    private var usbPath : String? = null;

    @SuppressLint("SetTextI18n")
    fun setupUSB(usbDevice: UsbDevice) {

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbConnection = manager.openDevice(usbDevice)

        val fd = usbConnection?.fileDescriptor
        usbPath = usbDevice.deviceName

        val vid = usbDevice.vendorId
        val pid = usbDevice.productId

        Log.d("gr", "#################### NEW RUN ###################")
        Log.d("gr", "Found fd: $fd  usbfs_path: $usbPath")
        Log.d("gr", "Found vid: $vid  pid: $pid")

    }

    override fun onStop() {
        fgStop()
        super.onStop()
    }

    private external fun fgInit(fd: Int, usbfsPath: String): Void
    private external fun fgStart(fileName: String): Void
    private external fun fgStop(): Void

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
}
