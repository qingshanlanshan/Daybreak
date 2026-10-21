package com.qshlab.daybreak

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.qshlab.daybreak.cal.Cal
import com.qshlab.daybreak.cal.dueDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val OFFSET = intPreferencesKey("month")   // months from this one
/** The intent extra MainActivity reads: a date ("2026-10-22") to start a new task on, or "calendar". */
const val EXTRA_NEW_TASK = "newTaskOn"
const val EXTRA_OPEN = "open"

private val ink = ColorProvider(day = Color(0xFF1B2130), night = Color(0xFFE5E8EE))
private val muted = ColorProvider(day = Color(0xFF9AA1AE), night = Color(0xFF5F6675))
private val bg = ColorProvider(day = Color(0xFFF6F7F9), night = Color(0xFF1A1D24))
private val accent = ColorProvider(day = Color(0xFF3F51D1), night = Color(0xFF8B9BFF))
private val onAccent = ColorProvider(day = Color.White, night = Color(0xFF0F1430))

/** Home-screen month: ‹ October 2026 ›, the days (a dot where something's on), tap a day to add a task due then. */
class MonthWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Month(currentState<Preferences>()[OFFSET] ?: 0) }
    }

    @Composable
    private fun Month(offset: Int) {
        val today = LocalDate.now()
        val month = YearMonth.from(today).plusMonths(offset.toLong())
        val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val end = start.plusWeeks(6)
        // Days with an event or a task due (not done).
        val busy = runCatching {
            Cal.occurrences(start, end).map { it.start.toLocalDate() }.toSet() +
                Cal.tasks().filter { !it.task.done }.mapNotNull { it.task.dueDate() }
        }.getOrDefault(emptySet())
        Column(GlanceModifier.fillMaxSize().background(bg).cornerRadius(18.dp).padding(horizontal = 6.dp, vertical = 8.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", GlanceModifier.size(36.dp).clickable(actionRunCallback<PrevMonth>()), style = TextStyle(color = ink, fontSize = 20.sp, textAlign = TextAlign.Center))
                Text(month.month.getDisplayName(JTextStyle.FULL, Locale.getDefault()) + " " + month.year,
                    GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>(actionParametersOf(OPEN to "calendar"))),
                    style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                Text("›", GlanceModifier.size(36.dp).clickable(actionRunCallback<NextMonth>()), style = TextStyle(color = ink, fontSize = 20.sp, textAlign = TextAlign.Center))
            }
            Row(GlanceModifier.fillMaxWidth()) {
                for (i in 0 until 7) Text(DayOfWeek.MONDAY.plus(i.toLong()).getDisplayName(JTextStyle.NARROW, Locale.getDefault()), GlanceModifier.defaultWeight(),
                    style = TextStyle(color = muted, fontSize = 11.sp, textAlign = TextAlign.Center))
            }
            for (w in 0 until 6) Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                for (d in 0 until 7) {
                    val day = start.plusDays((w * 7 + d).toLong())
                    val isToday = day == today
                    Box(GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity<MainActivity>(actionParametersOf(NEW_TASK to day.toString()))),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(GlanceModifier.size(26.dp).cornerRadius(13.dp).let { if (isToday) it.background(accent) else it }, contentAlignment = Alignment.Center) {
                                Text(day.dayOfMonth.toString(), style = TextStyle(
                                    color = if (isToday) onAccent else if (YearMonth.from(day) == month) ink else muted,
                                    fontSize = 13.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center))
                            }
                            Text(if (day in busy) "•" else " ", style = TextStyle(color = accent, fontSize = 18.sp, textAlign = TextAlign.Center))
                        }
                    }
                }
            }
        }
    }

    companion object {
        val NEW_TASK = ActionParameters.Key<String>(EXTRA_NEW_TASK)
        val OPEN = ActionParameters.Key<String>(EXTRA_OPEN)

        /** Redraws every Daybreak widget (after tasks or events change). */
        suspend fun refresh(ctx: Context) = runCatching { MonthWidget().updateAll(ctx) }
    }
}

private suspend fun shift(ctx: Context, id: GlanceId, by: Int) {
    updateAppWidgetState(ctx, PreferencesGlanceStateDefinition, id) { it.toMutablePreferences().apply { this[OFFSET] = (this[OFFSET] ?: 0) + by } }
    MonthWidget().update(ctx, id)
}

class PrevMonth : ActionCallback { override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = shift(context, glanceId, -1) }
class NextMonth : ActionCallback { override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = shift(context, glanceId, 1) }

class MonthWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = MonthWidget() }
