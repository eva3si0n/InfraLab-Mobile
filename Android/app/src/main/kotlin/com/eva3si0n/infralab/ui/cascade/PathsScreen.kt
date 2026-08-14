package com.eva3si0n.infralab.ui.cascade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eva3si0n.infralab.data.ApiPathInfo
import com.eva3si0n.infralab.ui.AppViewModel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// Транзит: через какие автономные системы идёт путь от RU-входа до эндпоинта плеча.
//
// Меряется по АНДЕРЛЕЮ (traceroute до эндпоинта), а не сквозь туннель: RTT сквозь туннель
// показал бы деградацию транзита только когда она уже съела задержку, и не сказал бы, где.
//
// ⚠️ Флаг — страна, где реально стоят ХОПЫ этой AS, а НЕ страна её регистрации. AS6939
// (Hurricane Electric) зарегистрирована в США, а её маршрутизаторы на нашем пути — в Хельсинки.
//
// Цепочка нарисована СВЕРХУ ВНИЗ, в отличие от веб-панели: на телефоне горизонтальная
// всё равно переносится и перестаёт читаться как путь.

private val TZ = ZoneOffset.ofHours(5)      // MSK+2 (UTC+5), как везде в приложении
private val TF = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("ru"))

private fun fmtTime(unix: Double?): String =
    if (unix == null || unix <= 0) "—"
    else TF.format(Instant.ofEpochSecond(unix.toLong()).atZone(TZ))

/** Флаг страны из двухбуквенного кода. Пусто, если кода нет: выдумывать нельзя. */
private fun flag(cc: String?): String {
    if (cc == null || cc.length != 2) return ""
    val base = 0x1F1E6
    val sb = StringBuilder()
    for (c in cc.uppercase()) sb.appendCodePoint(base + (c.code - 'A'.code))
    return "$sb "
}

/** «1 хоп», «3 хопа», «5 хопов» — «3 хопов» бросается в глаза. */
private fun plural(n: Int): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "хоп"
        m10 in 2..4 && m100 !in 12..14 -> "хопа"
        else -> "хопов"
    }
}

private fun asPath(s: String?): String =
    (s ?: "").split(" ").filter { it.isNotBlank() }.joinToString(" → ") { "AS$it" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathsScreen(vm: AppViewModel, onBack: () -> Unit) {
    var paths by remember { mutableStateOf<List<ApiPathInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        paths = vm.fetchCascadePayload()?.paths ?: emptyList()
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Транзит") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
        )
    }) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                loading && paths.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                paths.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("Зонд ещё не отработал или vpncascade недоступен",
                            style = MaterialTheme.typography.bodySmall)
                    }
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val hosts = paths.map { it.host }.distinct()
                    items(hosts) { host ->
                        ElevatedCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(host, style = MaterialTheme.typography.titleSmall)
                                paths.filter { it.host == host }.forEach { LegBlock(it) }
                            }
                        }
                    }
                    item {
                        Text(
                            "Путь до эндпоинта каждого плеча, замер раз в 12 минут портом самого плеча. " +
                                "Флаг — где реально стоят хопы этой AS, а не где она зарегистрирована.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegBlock(p: ApiPathInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p.leg.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
            if (p.stale) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "зонд молчит",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${p.hops ?: 0} ${plural(p.hops ?: 0)} · с ${fmtTime(p.changedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        p.asns.forEachIndexed { i, a ->
            Row(verticalAlignment = Alignment.Top) {
                // Вертикальный соединитель: точка на звене, чёрточка между звеньями.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(10.dp)
                ) {
                    Box(
                        Modifier.padding(top = 5.dp).size(6.dp)
                            .background(MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    if (i < p.asns.size - 1) {
                        Box(
                            Modifier.width(1.5.dp).height(18.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "AS${a.num}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (a.name != null || a.cc != null) {
                        Text(
                            "${flag(a.cc)}${a.name ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // История: только ПРЕДЫДУЩИЕ состояния — текущее и есть цепочка выше. Если смена
        // одна, пишем это явно: пустота на месте истории читается как «данные потеряли»
        // (поймано на веб-панели 13.08.2026).
        if (p.changes.size > 1) {
            Column(Modifier.padding(start = 18.dp, top = 2.dp)) {
                p.changes.drop(1).forEach { c ->
                    Text(
                        "до ${fmtTime(c.time)} · ${asPath(c.asPath)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (p.changes.size == 1) {
            Text(
                "путь не менялся с ${fmtTime(p.changes[0].time)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp)
            )
        }
    }
}
