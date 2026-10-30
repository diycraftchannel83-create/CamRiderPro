package com.example.ui.components
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GpsTelemetry
import com.example.data.model.OverlayConfig
import com.example.data.model.SpeedUnit
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.HudMuted
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
@Composable
fun TelemetryVideoBannerOverlay(telemetry: GpsTelemetry,speedUnit: SpeedUnit,overlayConfig: OverlayConfig,modifier: Modifier=Modifier) {
 var dateTime by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd|HH:mm:ss",Locale.getDefault()).format(Date())) }
 LaunchedEffect(Unit) { while(true) { dateTime=SimpleDateFormat("yyyy-MM-dd|HH:mm:ss",Locale.getDefault()).format(Date()); delay(1000) } }
 val max=telemetry.maxSpeedKmh*speedUnit.multiplierFromKmh
 val avg=telemetry.avgSpeedKmh*speedUnit.multiplierFromKmh
 Row(modifier.fillMaxWidth().background(CockpitBlack.copy(alpha=.60f),RoundedCornerShape(7.dp)).padding(horizontal=5.dp,vertical=7.dp).testTag("telemetry_video_banner"),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
  Stat("DATE / TIME",dateTime.replace("|","\n"),Color.White,Modifier.weight(1f))
  if(overlayConfig.showCoordinates) {
   Stat("LAT / LON",String.format(Locale.US,"%.5f\n%.5f",telemetry.latitude,telemetry.longitude),Color(0xFF45F4FF),Modifier.weight(1f))
  }
  Stat("ALT","${telemetry.altitudeMeters.toInt()} m",Color.White,Modifier.weight(1f))
  Stat("DIST",String.format(Locale.US,"%.2f km",telemetry.distanceKm),Color.White,Modifier.weight(1f))
  Stat("MAX","${max.toInt()} ${speedUnit.label}",Color(0xFF00F5A0),Modifier.weight(1f))
  Stat("AVG","${avg.toInt()} ${speedUnit.label}",Color(0xFF00F5A0),Modifier.weight(1f))
 }
}
@Composable
private fun Stat(label:String,value:String,color:Color,modifier:Modifier=Modifier) {
 Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
  Text(label,color=HudMuted,fontSize=8.sp,fontWeight=FontWeight.Bold,maxLines=1)
  Text(value,color=color,fontSize=9.sp,fontWeight=FontWeight.Bold,fontFamily=FontFamily.Monospace,lineHeight=11.sp,maxLines=2)
 }
}