package com.linktomac.ui

import com.journeyapps.barcodescanner.ScanOptions

fun qrScanOptions(): ScanOptions = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("Scan the pairing code shown on your Mac")
    setBeepEnabled(false)
    setOrientationLocked(true)
}
