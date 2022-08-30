package com.example.seoulbikeapplication

import android.Manifest

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Geocoder
import android.location.GnssAntennaInfo
import android.location.Location
import android.os.AsyncTask
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem

import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction

import com.example.seoulbikeapplication.databinding.ActivityMainBinding
import com.example.seoulbikeapplication.databinding.NavigationHeaderBinding
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.material.navigation.NavigationView
import com.google.maps.android.SphericalUtil
import com.kakao.sdk.common.util.Utility
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

lateinit var dbHelper: DBHelper
lateinit var database: SQLiteDatabase
class MainActivity : AppCompatActivity(), OnMapReadyCallback,
    GoogleApiClient.ConnectionCallbacks,
    GoogleApiClient.OnConnectionFailedListener,
    GoogleMap.OnMarkerClickListener{
    lateinit var binding : ActivityMainBinding
    lateinit var toogle : ActionBarDrawerToggle
    lateinit var menu : Menu
    lateinit var apiClient : GoogleApiClient
    lateinit var providerClient : FusedLocationProviderClient
    var googleMap: GoogleMap? = null
    lateinit var geocoder : Geocoder
    lateinit var sharedPreferences: SharedPreferences
    var pin : LatLng? = null

    val API_KEY = "4a72487879736f7935365047766f77"
    var task: BikeReadTask? = null
    var bikes = JSONArray()
    val bitmap by lazy {
        val bitmap = ResourcesCompat.getDrawable(resources, R.drawable.bikeicon, null)?.toBitmap()
        Bitmap.createScaledBitmap(bitmap!!, 157, 130, false)
    }
    fun JSONArray.merge(anotherArray: JSONArray) {
        for (i in 0 until anotherArray.length()) {
            this.put(anotherArray.get(i))
        }
    }
    fun readData(startIndex: Int, lastIndex: Int): JSONObject {
        val url = URL("http://openAPI.seoul.go.kr:8088" + "/${API_KEY}/json/bikeStationMaster/${startIndex}/${lastIndex}")
        val connection = url.openConnection()
        val data = connection.getInputStream().readBytes().toString(charset("UTF-8"))
        return JSONObject(data)
    }
    inner class BikeReadTask : AsyncTask<Void, JSONArray, String>() {
        override fun onPreExecute() {
            googleMap?.clear()
            bikes = JSONArray()
        }

        override fun doInBackground(vararg p0: Void?): String {
            val step = 1000
            var startIndex = 1
            var lastIndex = step
            var totalCount = 0

            do {
                if(isCancelled) break

                if(totalCount != 0) {
                    startIndex += step
                    lastIndex += step
                }

                val jsonObject = readData(startIndex, lastIndex)

                totalCount = jsonObject.getJSONObject("bikeStationMaster").getInt("list_total_count")

                val rows = jsonObject.getJSONObject("bikeStationMaster").getJSONArray("row")

                bikes.merge(rows)

                publishProgress(rows)
            } while (lastIndex < totalCount)

            return "complete"
        }

        override fun onProgressUpdate(vararg values: JSONArray?) {
            val array = values[0]
            array?.let {
                for (i in 0 until array.length()) {
                    addMarkers(array.getJSONObject(i))
                }
            }
        }
    }

    fun addMarkers(bike: JSONObject) {
        googleMap?.addMarker(
            MarkerOptions()
                .position(LatLng(bike.getDouble("STATN_LAT"), bike.getDouble("STATN_LNT")))
                .title(bike.getString("STATN_ADDR1")+" "+bike.getString("STATN_ADDR2"))
                .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(R.layout.activity_main)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = DBHelper(this, "mydb.db", null, 1)
        database = dbHelper.writableDatabase

        setSupportActionBar(binding.toolbar)
        toogle = ActionBarDrawerToggle(this, binding.drawer, R.string.drawer_open, R.string.drawer_close)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toogle.syncState()
        geocoder = Geocoder(this)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val bgColor = sharedPreferences.getString("color", "")
        val colorDrawable : ColorDrawable
        colorDrawable = ColorDrawable(Color.parseColor(bgColor))
        supportActionBar?.setBackgroundDrawable(colorDrawable)

        (supportFragmentManager.findFragmentById(R.id.mapView) as SupportMapFragment)!!.getMapAsync(this)

        providerClient = LocationServices.getFusedLocationProviderClient(this)
        apiClient = GoogleApiClient.Builder(this)
            .addApi(LocationServices.API)
            .addConnectionCallbacks(this)
            .addOnConnectionFailedListener(this)
            .build()

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) {
            if(it.all { permission -> permission.value == true}) {
                apiClient.connect()
            } else {
                Toast.makeText(this, "권한 거부..", Toast.LENGTH_SHORT).show()
            }
        }
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !== PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !== PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE) !== PackageManager.PERMISSION_GRANTED)
        {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_NETWORK_STATE)
            )
        }
        else {
            apiClient.connect()
        }
        //val keyHash = Utility.getKeyHash(this)
        //Log.d("mobileApp", keyHash)

        val login = findViewById(R.id.main_drawer_view) as NavigationView
        var txtlogin = login.menu.findItem(R.id.txtLogin)
        txtlogin.setOnMenuItemClickListener {
            val intent = Intent(this, AuthActivity::class.java)
            if (binding.btnLogin.text.equals("로그인") )
                intent.putExtra("data", "logout")
            else if (binding.btnLogin.text.equals("로그아웃"))
                intent.putExtra("data", "login")
            startActivity(intent)
            true
        }
        var route = login.menu.findItem(R.id.route)
        var map = login.menu.findItem(R.id.map)
        var set = login.menu.findItem(R.id.setting)
        route.setOnMenuItemClickListener {
            val fragmentManager : FragmentManager = supportFragmentManager
            val transaction : FragmentTransaction = fragmentManager.beginTransaction()
            var fragment = Fragment1()
            transaction.add(R.id.fragment_content, fragment)
            transaction.commit()
            true
        }
        map.setOnMenuItemClickListener {
            val transaction = supportFragmentManager.beginTransaction()
            val frameLayout = supportFragmentManager.findFragmentById(R.id.fragment_content)
            transaction.remove(frameLayout!!)
            transaction.commit()
            true
        }

        set.setOnMenuItemClickListener {
            val intent = Intent(this, SettingActivity::class.java)
            startActivity(intent)
            true
        }
    }

    override fun onMapReady(p0: GoogleMap?) {
        googleMap = p0
        googleMap?.setOnMarkerClickListener(this)
    }

    private fun moveMap(latitude:Double, longitude:Double) {
        val latLng = LatLng(latitude,longitude)
        val position: CameraPosition = CameraPosition.Builder()
            .target(latLng)
            .zoom(16f)
            .build()
        googleMap!!.moveCamera(CameraUpdateFactory.newCameraPosition(position))

        val markerOp = MarkerOptions()
        markerOp.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        markerOp.position(latLng)
        markerOp.title("내 위치")
        googleMap?.addMarker(markerOp)
    }
    override fun onConnected(p0: Bundle?) {
        //TODO("Not yet implemented")
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)===PackageManager.PERMISSION_GRANTED) {
            providerClient.lastLocation.addOnSuccessListener(
                this@MainActivity,
                object:OnSuccessListener<Location>{
                    override fun onSuccess(p0: Location?) {
                        p0?.let{
                            val latitude = p0.latitude
                            val longitude = p0.longitude
                            pin = LatLng(latitude, longitude)
                            Log.d("mobileApp", "lat: $latitude, lng: $longitude")
                            moveMap(latitude,longitude)
                        }
                    }
                }
            )
            apiClient.disconnect()
        }
    }

    override fun onConnectionFailed(p0: ConnectionResult) {
        //TODO("Not yet implemented")
    }

    override fun onConnectionSuspended(p0: Int) {
        //TODO("Not yet implemented")
    }

    override fun onMarkerClick(p0: Marker?): Boolean {
        var distance : Double
        distance = SphericalUtil.computeDistanceBetween(pin, p0?.position)
        var time : Int
        time = distance.toInt() * 15 / 1000
        Toast.makeText(this, "${distance.toInt()}m 👣 ${time}분", Toast.LENGTH_LONG).show()
        return false
    }
    override fun onResume() {
        super.onResume()
        val bgColor = sharedPreferences.getString("color","")
        setSupportActionBar(binding.toolbar)
        val colorDrawable : ColorDrawable
        colorDrawable = ColorDrawable(Color.parseColor(bgColor))
        supportActionBar?.setBackgroundDrawable(colorDrawable)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val menuSearch = menu?.findItem(R.id.menu_search)
        val searchView = menuSearch?.actionView as SearchView
        searchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                //TODO("Not yet implemented")
                return true
            }

            override fun onQueryTextSubmit(query: String?): Boolean {
                //TODO("Not yet implemented")
                val address = geocoder.getFromLocationName(query, 1)
                val latLng = LatLng(address[0].latitude,address[0].longitude)
                val position: CameraPosition = CameraPosition.Builder()
                    .target(latLng)
                    .zoom(16f)
                    .build()
                googleMap!!.moveCamera(CameraUpdateFactory.newCameraPosition(position))
                pin = latLng
                var location = "INSERT INTO locs('loc') values('${query}');"
                database.execSQL(location)
                return true
            }
        })
        this.menu = menu
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if(toogle.onOptionsItemSelected(item)) return true

        return super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        task?.cancel(true)
        task = BikeReadTask()
        task?.execute()

        val navView = findViewById(R.id.main_drawer_view) as NavigationView
        var menuItem = navView.menu.findItem(R.id.txtLogin)
        var menuItem2 = navView.menu.findItem(R.id.route)
        var menuItem3 = navView.menu.findItem(R.id.authTv)
        var menuItem4 = navView.menu.findItem(R.id.map)
        var menuItem5 = navView.menu.findItem(R.id.setting)
        if (MyApplication.checkAuth() || MyApplication.email != null) {
            binding.btnLogin.text = "로그아웃"
            menuItem.title = "로그아웃"
            menuItem2.isVisible = true
            val nickname = sharedPreferences.getString("id","")
            if(nickname == "") {
                menuItem3.title = "${MyApplication.email}님 반갑습니다."
            }
            else {
                menuItem3.title = "${nickname}님 반갑습니다."
            }
            menuItem3.isVisible = true
            menuItem4.isVisible = true
            menuItem5.isVisible = true
        }
        else {
            binding.btnLogin.text = "로그인"
            menuItem.title = "로그인"
            menuItem2.isVisible = false
            menuItem3.title = ""
            menuItem3.isVisible = false
            menuItem4.isVisible = false
            menuItem5.isVisible = false
        }
    }

    override fun onStop() {
        super.onStop()
        task?.cancel(true)
        task = null
    }
}