package kairos.proyecto

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

/**
 * Mapa con un marcador (adaptado de MapWithMarker, android-samples).
 * Usa FragmentActivity y no AppCompatActivity porque el tema de Kairos no es de AppCompat.
 */
class MapsActivity : FragmentActivity(), OnMapReadyCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        val cdmx = LatLng(19.4326, -99.1332)
        googleMap.addMarker(MarkerOptions().position(cdmx).title("Ciudad de México"))
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(cdmx, 11f))   // 11 = ciudad completa; 15 = calles
    }
}
