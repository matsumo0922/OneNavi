package me.matsumo.onenavi.feature.map.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.collections.immutable.ImmutableList

@Composable
  internal fun MapPolyline(
    googleMap: GoogleMap,
    points: ImmutableList<LatLng>,
    color: Color = Color(0xFF1E88E5),
    widthPx: Float = 12f,
  ) {
      DisposableEffect(googleMap, points, color, widthPx) {                                                                                              
          val polyline = googleMap.addPolyline(                                                                                                          
              PolylineOptions()
                  .addAll(points)                                                                                                                        
                  .color(color.toArgb())                                    
                  .width(widthPx),                                                                                                                       
          )
          onDispose { polyline.remove() }                                                                                                                
      }                                                                     
  }  