package com.sdk.glassessdksample.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import com.sdk.glassessdksample.R

class LoadingDialogHelper(private val context: Context) {
    private var dialog: AlertDialog? = null
    
    fun show(message: String = "Loading...") {
        if (dialog?.isShowing == true) return
        
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_loading, null)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val textView = view.findViewById<TextView>(R.id.tvLoadingMessage)
        textView.text = message
        
        dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(false)
            .create()
        
        dialog?.show()
    }
    
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
    
    fun isShowing(): Boolean = dialog?.isShowing == true
    
    fun close() = dismiss()
}
