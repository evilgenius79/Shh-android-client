package io.github.evilgenius79.shh.security

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import androidx.core.content.ContextCompat
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.DeviceFilter
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.Ctap2Session
import java.io.IOException

/**
 * Finds security keys over NFC and USB with the Yubico SDK, which despite its name speaks to the
 * FIDO2 keys of any maker: over NFC every key is a smart card, and over USB the filter below lets
 * other makers' keys in.
 */
class YubiKitFinder(private val activity: Activity) : SecurityKeyFinder {
    private val manager = YubiKitManager(activity)
    private var nfcStarted = false

    override val nfc: SecurityKeyFinder.Nfc
        get() {
            val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return SecurityKeyFinder.Nfc.ABSENT
            return if (adapter.isEnabled) SecurityKeyFinder.Nfc.READY else SecurityKeyFinder.Nfc.SWITCHED_OFF
        }

    override val usb: Boolean get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    override fun start(onKey: (SecurityKeyLink) -> Unit) {
        if (usb) {
            manager.startUsbDiscovery(UsbConfiguration().setDeviceFilter(AnyMakersKey(activity))) { device -> onKey(link(device)) }
        }
        try {
            // Signing takes the key a moment, and a PIN makes for several exchanges.
            manager.startNfcDiscovery(NfcConfiguration().timeout(NFC_TIMEOUT_MS), activity) { device -> onKey(link(device)) }
            nfcStarted = true
        } catch (_: NfcNotAvailable) {
            // No NFC here, or it is switched off: [nfc] says which, and USB may still do.
        }
    }

    override fun stop() {
        manager.stopUsbDiscovery()
        if (nfcStarted) {
            manager.stopNfcDiscovery(activity)
            nfcStarted = false
        }
    }

    private fun link(device: YubiKeyDevice) = object : SecurityKeyLink {
        override fun <T> perform(task: SecurityKeyTask<T>, pin: CharArray?, onWaitingForTouch: () -> Unit, calledOff: () -> Boolean): T {
            try {
                // Over USB a security key is a HID device; over NFC, and for some keys over USB too, a smart card.
                return if (device.supportsConnection(FidoConnection::class.java)) {
                    device.openConnection(FidoConnection::class.java).use { connection ->
                        SecurityKeyClient.perform({ Ctap2Session(connection) }, task, pin, onWaitingForTouch, calledOff)
                    }
                } else {
                    device.openConnection(SmartCardConnection::class.java).use { connection ->
                        SecurityKeyClient.perform({ Ctap2Session(connection) }, task, pin, onWaitingForTouch, calledOff)
                    }
                }
            } catch (e: IOException) {
                throw SecurityKeyException.Failed(SecurityKeyClient.LINK_BROKE, retry = true, cause = e)
            } catch (e: IllegalStateException) {
                // What the SDK throws for a device it has no access to, or that is not what it seemed.
                throw SecurityKeyException.Failed("This device could not be used as a security key: ${e.message}", retry = true, cause = e)
            }
        }
    }

    /**
     * Lets in the USB devices that may be security keys, whoever made them: those with a HID
     * interface that is not a keyboard or a mouse, or with a smart card interface. The system
     * asks the user before the app may talk to any of them.
     */
    private class AnyMakersKey(private val context: Context) : DeviceFilter() {
        override fun checkVendorProductIds(vendorId: Int, productId: Int): Boolean {
            val usb = ContextCompat.getSystemService(context, UsbManager::class.java) ?: return false
            return usb.deviceList.values.any { device ->
                device.vendorId == vendorId && device.productId == productId && (0 until device.interfaceCount).any { index ->
                    val usbInterface = device.getInterface(index)
                    when (usbInterface.interfaceClass) {
                        UsbConstants.USB_CLASS_HID -> usbInterface.interfaceSubclass != UsbConstants.USB_INTERFACE_SUBCLASS_BOOT
                        UsbConstants.USB_CLASS_CSCID -> true
                        else -> false
                    }
                }
            }
        }
    }

    private companion object {
        const val NFC_TIMEOUT_MS = 5_000
    }
}
