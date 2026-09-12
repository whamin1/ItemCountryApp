package com.bignerdranch.android.myapplication

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bignerdranch.android.myapplication.ui.AddedActivity
import com.bignerdranch.android.myapplication.ui.itemcountry.ItemCountryViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {

    private lateinit var insetsController: WindowInsetsControllerCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ✅ 1) Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // ✅ 2) 상태바+네비게이션바 숨기기 (전체화면)
        insetsController = WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host) as NavHostFragment
        val navController = navHost.navController

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        // 메뉴 id와 fragment id가 일치하므로 한 줄로 연결 가능
        bottomNav.setupWithNavController(navController)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val focusedView = currentFocus

            if (focusedView is EditText) {
                val rect = Rect()
                focusedView.getGlobalVisibleRect(rect)

                if (!rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    focusedView.clearFocus()

                    val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager
                    keyboard.hideSoftInputFromWindow(focusedView.windowToken, 0)
                }
            }
        }

        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 포커스 얻을 때만 숨김 적용 (시스템 제스처 동작 안정적)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
