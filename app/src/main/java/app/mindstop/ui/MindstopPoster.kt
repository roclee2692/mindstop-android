package app.mindstop.ui

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R

class MindstopPoster : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext())
        val poster = layoutInflater.cloneInContext(builder.context)
            .inflate(R.layout.dialog_mindstop_poster, null)
        return builder.setView(poster)
            .setPositiveButton(R.string.close, null)
            .create()
    }
}
