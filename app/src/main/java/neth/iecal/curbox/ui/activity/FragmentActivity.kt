package neth.iecal.curbox.ui.activity

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import app.mindstop.ui.MindstopGrowthFragment
import app.mindstop.ui.MindstopOnboardingFragment
import app.mindstop.ui.MindstopRulesFragment
import app.mindstop.ui.MindstopSettingsFragment
import app.mindstop.ui.MindstopTodayFragment
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.fragments.installation.AccessibilityGuide
import neth.iecal.curbox.utils.BlurFadeAnimator

class FragmentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        val isFirstLaunchComplete = sharedPreferences.getBoolean("isFirstLaunchComplete", false)
        val requested = intent.getStringExtra("fragment")
        val selectedFragment = requested ?: if (!isFirstLaunchComplete) MindstopOnboardingFragment.FRAGMENT_ID else TAB_HOST

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_fragment)

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
        val bottomDivider = findViewById<View>(R.id.bottom_nav_divider)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPadding = if (bottomNav.isVisible) {
                ime.bottom
            } else {
                maxOf(systemBars.bottom, ime.bottom)
            }
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }

        when (selectedFragment) {
            MindstopOnboardingFragment.FRAGMENT_ID,
            AccessibilityGuide.FRAGMENT_ID -> {
                bottomNav.visibility = View.GONE
                bottomDivider.visibility = View.GONE
                val fragment = when (selectedFragment) {
                    MindstopOnboardingFragment.FRAGMENT_ID -> MindstopOnboardingFragment()
                    else -> AccessibilityGuide()
                }
                fragment.arguments = intent.extras
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_holder, fragment)
                    .commit()
            }
            else -> {
                bottomNav.visibility = View.VISIBLE
                bottomDivider.visibility = View.VISIBLE

                if (savedInstanceState == null) {
                    bottomNav.selectedItemId = R.id.nav_mindstop_today
                    showTab(R.id.nav_mindstop_today, commitNow = true)
                }

                bottomNav.setOnItemSelectedListener { item ->
                    if (item.itemId == bottomNav.selectedItemId) return@setOnItemSelectedListener false
                    switchTab(item.itemId)
                    true
                }
            }
        }

        if (savedInstanceState == null && !splashShownThisProcess) {
            splashShownThisProcess = true
            playBrandSplash()
        }
    }

    private fun playBrandSplash() {
        val overlay = findViewById<View>(R.id.mindstop_splash_overlay) ?: return
        val poster = findViewById<View>(R.id.mindstop_splash_poster) ?: return
        val brand = findViewById<View>(R.id.mindstop_splash_brand) ?: return
        val skip = findViewById<View>(R.id.mindstop_splash_skip) ?: return

        overlay.visibility = View.VISIBLE
        overlay.alpha = 1f

        poster.scaleX = 0.96f
        poster.scaleY = 0.96f
        poster.alpha = 0f
        brand.alpha = 0f
        brand.translationY = 18f
        skip.alpha = 0f

        poster.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(420L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        brand.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(140L)
            .setDuration(340L)
            .setInterpolator(OvershootInterpolator(1.04f))
            .start()

        skip.animate()
            .alpha(0.75f)
            .setStartDelay(260L)
            .setDuration(280L)
            .start()

        var dismissed = false
        val dismiss: () -> Unit = {
            if (!dismissed) {
                dismissed = true
                overlay.animate()
                    .alpha(0f)
                    .setDuration(300L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { overlay.visibility = View.GONE }
                    .start()
            }
        }
        overlay.setOnClickListener { dismiss() }
        Handler(Looper.getMainLooper()).postDelayed({ dismiss() }, SPLASH_HOLD_MS)
    }

    private val tabTags = mapOf(
        R.id.nav_mindstop_today to "tab_today",
        R.id.nav_mindstop_rules to "tab_rules",
        R.id.nav_mindstop_growth to "tab_growth",
        R.id.nav_mindstop_settings to "tab_settings",
    )

    private fun createTabFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_mindstop_today -> MindstopTodayFragment()
        R.id.nav_mindstop_rules -> MindstopRulesFragment()
        R.id.nav_mindstop_growth -> MindstopGrowthFragment()
        R.id.nav_mindstop_settings -> MindstopSettingsFragment()
        else -> MindstopTodayFragment()
    }

    private fun showTab(itemId: Int, commitNow: Boolean = false) {
        val tag = tabTags[itemId] ?: return
        val fm = supportFragmentManager
        fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val transaction = fm.beginTransaction()
        for ((id, otherTag) in tabTags) {
            if (id != itemId) {
                fm.findFragmentByTag(otherTag)?.let { transaction.hide(it) }
            }
        }

        val target = fm.findFragmentByTag(tag)
        if (target == null) {
            transaction.add(R.id.fragment_holder, createTabFragment(itemId), tag)
        } else {
            transaction.show(target)
        }

        if (commitNow) transaction.commitNow() else transaction.commit()
    }

    private fun switchTab(itemId: Int) {
        val container = findViewById<View>(R.id.fragment_holder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlurFadeAnimator.fadeOut(container) {
                showTab(itemId, commitNow = true)
                BlurFadeAnimator.fadeIn(container)
            }
        } else {
            showTab(itemId)
        }
    }

    companion object {
        private const val TAB_HOST = "mindstop_tabs"
        private const val SPLASH_HOLD_MS = 1_400L
        private var splashShownThisProcess = false
    }
}
