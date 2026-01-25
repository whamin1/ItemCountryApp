package com.bignerdranch.android.myapplication

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ✅ 1) Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // ✅ 2) 상태바+네비게이션바 숨기기 (전체화면)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // ✅ 3) 사용자가 스와이프로 잠깐 시스템바를 볼 수 있게 (권장)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host) as NavHostFragment
        val navController = navHost.navController

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        // 메뉴 id와 fragment id가 일치하므로 한 줄로 연결 가능
        bottomNav.setupWithNavController(navController)

        // 뒤로가기 UX 개선(탭 최상위 목적지로 동작)
//        val appBarConfig = AppBarConfiguration(
//            setOf(R.id.homeFragment, R.id.addedFragment, R.id.settingsFragment)
//        )
//        setupActionBarWithNavController(navController, appBarConfig)
//
//    }
//
//    override fun onSupportNavigateUp(): Boolean {
//        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
//        return navHost.navController.navigateUp() || super.onSupportNavigateUp()
//    }



//    private fun showLogsBottomSheet(item: String, country: String) {
//        // VM 통해 조회 → 콜백에서 UI 표시
//        vm.getQuantityLogs(item, country, 50) { logs ->
//            val dialog = BottomSheetDialog(this)
//            val view = layoutInflater.inflate(R.layout.bottom_sheet_logs, null)
//            dialog.setContentView(view)
//
//            val listView = view.findViewById<ListView>(R.id.listLogs)
//            val fmt = SimpleDateFormat("yy/MM/dd HH:mm", Locale.getDefault())
//            val rows = logs.map { l ->
//                "${fmt.format(Date(l.timestamp))}   ${l.fromHave} → ${l.toHave}  (＋${l.delta})"
//            }
//            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
//            dialog.show()
//        }
    }
}
