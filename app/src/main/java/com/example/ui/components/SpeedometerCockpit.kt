package com.example.ui.components
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GpsTelemetry
import com.example.data.model.SpeedUnit
import kotlin.math.cos
import kotlin.math.sin
@Composable
fun SpeedometerCockpit(telemetry: GpsTelemetry, speedUnit: SpeedUnit, showGearRpm: Boolean = true, isLandscape: Boolean = true, modifier: Modifier = Modifier) {
 val speed=(telemetry.speedKmh*speedUnit.multiplierFromKmh).coerceIn(0f,200f)
 val needle by animateFloatAsState(speed,tween(220),label="gps_speed_needle")
 val gauge=if(isLandscape) 218.dp else 252.dp
 Box(modifier.size(gauge).testTag("speedometer_gauge"),contentAlignment=Alignment.Center) {
  Canvas(Modifier.size(gauge)) {
   val cx=size.width/2f; val cy=size.height/2f; val r=size.minDimension*.445f; val sw=size.minDimension*.022f
   drawCircle(Color(0x33202D38),radius=r+sw*1.6f)
   drawArc(Color(0xFF45F4FF),135f,270f,false,Offset(cx-r,cy-r),Size(r*2,r*2),style=Stroke(sw,cap=StrokeCap.Round))
   drawArc(Color(0xFFFF304F),135f+270f*.82f,48.6f,false,Offset(cx-r,cy-r),Size(r*2,r*2),style=Stroke(sw*1.2f,cap=StrokeCap.Round))
   for(i in 0..100) {
    val a=Math.toRadians(135.0+270.0*i/100.0); val major=i%10==0; val mid=i%5==0
    val inner=r-if(major)18f else if(mid)13f else 8f; val outer=r-2f
    val c=if(i>=82) Color(0xFFFF304F) else Color(0xFF45F4FF).copy(alpha=if(major).95f else .48f)
    drawLine(c,Offset(cx+cos(a).toFloat()*inner,cy+sin(a).toFloat()*inner),Offset(cx+cos(a).toFloat()*outer,cy+sin(a).toFloat()*outer),if(major)3.2f else 1.2f,cap=StrokeCap.Round)
   }
   val a=Math.toRadians(135.0+270.0*needle/200f); val len=r*.77f
   val tip=Offset(cx+cos(a).toFloat()*len,cy+sin(a).toFloat()*len)
   val tail=Offset(cx-cos(a).toFloat()*r*.13f,cy-sin(a).toFloat()*r*.13f)
   drawLine(Color(0x66FF304F),tail,tip,12f,cap=StrokeCap.Round)
   drawLine(Color(0xFFFF304F),tail,tip,5f,cap=StrokeCap.Round)
   drawCircle(Color(0xFF111B25),12f,Offset(cx,cy)); drawCircle(Color(0xFF45F4FF),12f,Offset(cx,cy),style=Stroke(2f))
  }
  Column(horizontalAlignment=Alignment.CenterHorizontally) {
   Text("km/h",color=Color(0xFF45F4FF),fontSize=12.sp,fontWeight=FontWeight.SemiBold)
   Text(needle.toInt().toString(),color=Color.White,fontSize=if(isLandscape)42.sp else 48.sp,fontWeight=FontWeight.Black,fontFamily=FontFamily.Monospace)
   if(showGearRpm) Text("GEAR  ${telemetry.gear}",color=Color(0xFF00F5A0),fontSize=10.sp,fontWeight=FontWeight.Bold)
  }
 }
}