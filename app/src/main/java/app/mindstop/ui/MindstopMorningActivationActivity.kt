package app.mindstop.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.mindstop.core.BodyWakeActivity
import app.mindstop.core.MorningAlarm
import app.mindstop.core.MorningJoyPreset
import app.mindstop.core.MorningSessionRecord
import app.mindstop.core.RewardEngine
import app.mindstop.service.MindstopAlarmReceiver
import app.mindstop.service.MindstopAlarmScheduler
import app.mindstop.service.MindstopAlarmService
import app.mindstop.service.MorningRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityMindstopMorningActivationBinding

/**
 * Post-alarm 15-minute Morning Activation transition screen (v0.3 SPEC §11–§13).
 * Guides the user gently through Stage 1 (alarm acknowledged + pending reward),
 * Stage 2 (5m body wake checklist), and Stage 3 (10m morning joy expectation),
 * with honest self-reported completion or returned-to-bed transitions.
 */
class MindstopMorningActivationActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMindstopMorningActivationBinding
    private lateinit var repo: MorningRepository
    private val handler = Handler(Looper.getMainLooper())

    private var sessionId: Long = -1L
    private var alarmId: Long = -1L
    private var session: MorningSessionRecord? = null
    private var alarm: MorningAlarm = MorningAlarm()
    private var sensorManager: SensorManager? = null
    private var motionRecorded = false
    private var busy = false

    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val alarmRingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MindstopAlarmService.ACTION_ALARM_RINGING) return
            val ringAlarmId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)
            val ringSessionId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
            if (ringAlarmId > 0L) openActiveRingScreen(ringAlarmId, ringSessionId)
        }
    }

    private fun openActiveRingScreen(ringAlarmId: Long, ringSessionId: Long) {
        val ring = Intent(this, MindstopAlarmRingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, ringAlarmId)
            putExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, ringSessionId)
        }
        try {
            startActivity(ring)
        } catch (_: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindstopMorningActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MindstopUi.applySystemBarInsets(this, binding.activationRoot)
        MindstopUi.setupToolbar(this, binding.activationToolbar, R.string.mindstop_morning_act_title)

        repo = MorningRepository(this)
        sessionId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
        alarmId = intent.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)

        binding.activationCheck5Btn.setOnClickListener { confirm5m() }
        binding.activationCompleteBtn.setOnClickListener { completeActivation() }
        binding.activationBackToBedBtn.setOnClickListener { reportReturnedToBed() }

        loadSessionFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        busy = false
        motionRecorded = false
        loadSessionFromIntent(intent)
    }

    private fun loadSessionFromIntent(source: Intent) {
        sessionId = source.getLongExtra(MindstopAlarmReceiver.EXTRA_SESSION_ID, -1L)
        alarmId = source.getLongExtra(MindstopAlarmReceiver.EXTRA_ALARM_ID, -1L)
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val loadedSession = withContext(Dispatchers.IO) {
                if (sessionId > 0L) repo.getSession(sessionId) else repo.activeSession(now)
            }
            if (loadedSession == null) {
                finish()
                return@launch
            }
            session = loadedSession
            sessionId = loadedSession.id
            alarm = withContext(Dispatchers.IO) { repo.getAlarm(loadedSession.alarmId) } ?: MorningAlarm(id = alarmId)
            render()
            registerAuxiliaryMotionHint()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        ContextCompat.registerReceiver(
            this,
            alarmRingReceiver,
            IntentFilter(MindstopAlarmService.ACTION_ALARM_RINGING),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (MindstopAlarmService.isRinging()) {
            openActiveRingScreen(MindstopAlarmService.activeAlarmId, MindstopAlarmService.activeSessionId)
        }
    }

    override fun onPause() {
        try {
            unregisterReceiver(alarmRingReceiver)
        } catch (_: Exception) {
        }
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }

    private fun render() {
        binding.activationStage2Title.text = getString(R.string.mindstop_morning_act_stage2_title, alarm.bodyWakeMinutes)
        binding.activationStage3Title.text = getString(R.string.mindstop_morning_act_stage3_title, alarm.morningJoyMinutes)

        val checklist = binding.activationBodyChecklist
        checklist.removeAllViews()
        val items = alarm.bodyActivities.ifEmpty { BodyWakeActivity.DEFAULT_CODES }
        for (code in items) {
            checklist.addView(CheckBox(this).apply {
                text = bodyActivityLabel(code)
            })
        }

        val joyName = joyLabel(alarm.joyActivity)
        binding.activationJoyDesc.text = getString(
            R.string.mindstop_morning_act_joy_desc,
            joyName,
            alarm.joyAllowanceMinutes
        )
        if (session?.check5mConfirmed == true) {
            binding.activationCheck5Btn.isEnabled = false
            binding.activationCheck5Btn.setText(R.string.mindstop_morning_act_check5_done)
        }
        updateCountdown()
    }

    private fun updateCountdown() {
        val s = session ?: return
        val startWall = s.acknowledgedWall ?: s.ringStartedWall
        val totalMs = alarm.totalActivationMinutes * 60_000L
        val leftMs = (startWall + totalMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val mins = (leftMs / 60_000L).toInt()
        val secs = ((leftMs % 60_000L) / 1_000L).toInt()
        binding.activationCountdown.text = if (leftMs > 0L) {
            getString(R.string.mindstop_morning_act_remaining, "%02d:%02d".format(mins, secs))
        } else {
            getString(R.string.mindstop_morning_act_ready)
        }
    }

    private fun confirm5m() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        binding.activationCheck5Btn.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.markCheck5m(sid, System.currentTimeMillis()) }
            binding.activationCheck5Btn.setText(R.string.mindstop_morning_act_check5_done)
            Toast.makeText(this@MindstopMorningActivationActivity, R.string.mindstop_morning_act_check5_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun completeActivation() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val (_, confirmed) = withContext(Dispatchers.IO) {
                repo.completeActivation(sid, System.currentTimeMillis())
            }
            MindstopAlarmScheduler.cancelActivationCheckins(this@MindstopMorningActivationActivity, sid)
            val msg = if (confirmed) {
                getString(R.string.mindstop_morning_act_completed_reward, RewardEngine.MORNING_ENERGY)
            } else {
                getString(R.string.mindstop_morning_act_completed_plain)
            }
            Toast.makeText(this@MindstopMorningActivationActivity, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun reportReturnedToBed() {
        val sid = sessionId.takeIf { it > 0L } ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.reportReturnedToBed(sid, System.currentTimeMillis()) }
            MindstopAlarmScheduler.cancelActivationCheckins(this@MindstopMorningActivationActivity, sid)
            Toast.makeText(this@MindstopMorningActivationActivity, R.string.mindstop_morning_act_returned_toast, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Auxiliary motion hint only (v0.3 §12): recorded passively, never auto-completes wake-up. */
    private fun registerAuxiliaryMotionHint() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (motionRecorded || sessionId <= 0L) return
        motionRecorded = true
        sensorManager?.unregisterListener(this)
        lifecycleScope.launch(Dispatchers.IO) { repo.markMotionHint(sessionId, System.currentTimeMillis()) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun bodyActivityLabel(code: String): String = when (code) {
        BodyWakeActivity.WARM_CLOTHES.code -> getString(R.string.mindstop_morning_body_clothes)
        BodyWakeActivity.WASH_FACE.code -> getString(R.string.mindstop_morning_body_wash)
        BodyWakeActivity.DRINK_WATER.code -> getString(R.string.mindstop_morning_body_water)
        BodyWakeActivity.TURN_ON_LIGHT.code -> getString(R.string.mindstop_morning_body_light)
        BodyWakeActivity.LIGHT_STRETCH.code -> getString(R.string.mindstop_morning_body_stretch)
        else -> code
    }

    private fun joyLabel(code: String): String = when (code) {
        MorningJoyPreset.MUSIC.code -> getString(R.string.mindstop_morning_joy_music)
        MorningJoyPreset.AUDIOBOOK.code -> getString(R.string.mindstop_morning_joy_audiobook)
        MorningJoyPreset.BREAKFAST.code -> getString(R.string.mindstop_morning_joy_breakfast)
        MorningJoyPreset.SHORT_GAME.code -> getString(R.string.mindstop_morning_joy_game)
        MorningJoyPreset.GROWTH_GARDEN.code -> getString(R.string.mindstop_morning_joy_garden)
        else -> code.ifBlank { getString(R.string.mindstop_morning_joy_music) }
    }
}
