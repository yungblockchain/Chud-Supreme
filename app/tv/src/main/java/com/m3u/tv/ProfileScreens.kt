package com.m3u.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/* -------------------------------------------------------------------------------------------------
 * Who's watching: the picker that opens the app, and the Profiles settings tab.
 * ---------------------------------------------------------------------------------------------- */

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val store: ProfileStore,
) : ViewModel() {
    val profiles: StateFlow<List<Profile>> = store.profiles
    val active: StateFlow<Profile?> = store.active
    val lastUsedId: String? get() = store.lastUsedId

    fun select(profile: Profile, pin: String?): Boolean = store.select(profile, pin)
    fun lock() = store.lock()
    fun unlock(pin: String): Boolean = store.unlock(pin)
    fun add(name: String, face: String, pin: String?, kids: Boolean, minutes: Int) = store.add(name, face, pin, kids, minutes)
    fun rename(id: String, name: String) = store.update(id) { it.copy(name = name.trim().take(24).ifBlank { it.name }) }
    fun setFace(id: String, face: String) = store.update(id) { it.copy(face = face) }
    fun setKids(id: String, kids: Boolean) = store.update(id) { it.copy(kids = kids) }
    fun setMinutes(id: String, minutes: Int) = store.update(id) { it.copy(kidsMinutes = minutes.coerceIn(0, ProfileStore.MAX_KIDS_MINUTES)) }
    fun setPin(id: String, pin: String?) = store.setPin(id, pin)
    fun remove(id: String) = store.remove(id)
}

/** Full screen: faces in a row, OK picks; a PIN box appears for a locked profile. */
@Composable
fun ProfilePickerScreen(
    profiles: List<Profile>,
    lastUsedId: String?,
    onPick: (Profile, String?) -> Boolean,
) {
    var asking by remember { mutableStateOf<Profile?>(null) }
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val pinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    LaunchedEffect(asking) {
        if (asking == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { pinFocus.requestFocus() }
    }
    BackHandler(enabled = asking != null) {
        asking = null
        pin = ""
        wrong = false
    }
    val preferred = profiles.firstOrNull { it.id == lastUsedId } ?: profiles.firstOrNull()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(
                text = stringResource(R.string.dial_profiles_who),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Accent,
                fontSize = 36.sp,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(horizontal = 48.dp),
                modifier = Modifier.focusGroup(),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileFace(
                        profile = profile,
                        focusRequester = first.takeIf { profile.id == preferred?.id },
                        onClick = {
                            if (profile.hasPin) {
                                asking = profile
                                pin = ""
                                wrong = false
                            } else {
                                onPick(profile, null)
                            }
                        },
                    )
                }
            }
            asking?.let { profile ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(360.dp)) {
                        DialTextField(
                            label = stringResource(R.string.dial_profiles_pin_for, profile.name),
                            value = pin,
                            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                            readOnly = false,
                            secret = true,
                            focusRequester = pinFocus,
                            onDone = {
                                wrong = !onPick(profile, pin)
                                if (wrong) pin = ""
                            },
                        )
                    }
                    if (wrong) {
                        Text(
                            text = stringResource(R.string.dial_profiles_wrong_pin),
                            color = TvColors.Danger,
                            fontFamily = TvFonts.Body,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileFace(profile: Profile, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusFrame(
            onClick = onClick,
            shape = RoundedCornerShape(28.dp),
            focusRequester = focusRequester,
            focusedScale = 1.1f,
            semanticsLabel = profile.name,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(132.dp)
                    .background(TvColors.SurfaceRaised, RoundedCornerShape(28.dp)),
            ) {
                Text(text = profile.face, fontSize = 64.sp)
                if (profile.hasPin) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(28.dp)
                            .background(TvColors.Background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = TvColors.TextPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = profile.name,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(150.dp),
        )
        if (profile.kids) {
            Text(
                text = stringResource(R.string.dial_profiles_kids_badge),
                color = TvColors.Focus,
                fontFamily = TvFonts.Body,
                fontSize = 13.sp,
            )
        }
    }
}

/** Settings > Profiles: add, rename, PIN, kids mode and timer, remove, switch. */
@Composable
fun ProfilesSettingsScreen(
    onSwitchProfile: () -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var newFace by rememberSaveable { mutableStateOf(PROFILE_FACES.first()) }
    var newPin by rememberSaveable { mutableStateOf("") }
    var newKids by rememberSaveable { mutableStateOf(false) }
    var newMinutes by rememberSaveable { mutableStateOf(0) }
    var editingPin by rememberSaveable { mutableStateOf<String?>(null) }
    var pinDraft by rememberSaveable { mutableStateOf("") }
    val on = stringResource(R.string.dial_value_on)
    val off = stringResource(R.string.dial_value_off)

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 24.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = stringResource(R.string.dial_profiles_intro),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    text = stringResource(R.string.dial_profiles_add),
                    icon = Icons.Rounded.PersonAdd,
                    selected = adding,
                    onClick = { adding = !adding },
                )
                if (profiles.size > 1 || profiles.any { it.hasPin }) {
                    TvActionButton(
                        text = stringResource(R.string.dial_profiles_switch),
                        icon = Icons.Rounded.SwitchAccount,
                        onClick = onSwitchProfile,
                    )
                }
            }
        }
        if (adding) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 820.dp)) {
                    DialTextField(
                        label = stringResource(R.string.dial_profiles_name),
                        value = newName,
                        onValueChange = { newName = it.take(24) },
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        readOnly = false,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                        items(PROFILE_FACES, key = { it }) { face ->
                            TvActionButton(
                                text = face,
                                icon = Icons.Rounded.Check,
                                selected = face == newFace,
                                showTextWhenUnfocused = true,
                                onClick = { newFace = face },
                            )
                        }
                    }
                    DialTextField(
                        label = stringResource(R.string.dial_profiles_pin_optional),
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(8) },
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                        readOnly = false,
                        secret = true,
                    )
                    SettingRow(
                        label = stringResource(R.string.dial_profiles_kids),
                        value = if (newKids) on else off,
                        onClick = { newKids = !newKids },
                    )
                    if (newKids) {
                        SettingRow(
                            label = stringResource(R.string.dial_profiles_timer),
                            value = minutesLabel(newMinutes),
                            onClick = { newMinutes = TIMER_OPTIONS.nextAfter(newMinutes) },
                        )
                    }
                    TvActionButton(
                        text = stringResource(R.string.dial_profiles_create),
                        icon = Icons.Rounded.Check,
                        enabled = newName.isNotBlank(),
                        onClick = {
                            viewModel.add(newName, newFace, newPin.takeIf { it.isNotBlank() }, newKids, newMinutes)
                            adding = false
                            newName = ""
                            newPin = ""
                            newKids = false
                            newMinutes = 0
                        },
                    )
                }
            }
        }
        items(profiles, key = { it.id }) { profile ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSection(
                    "${profile.face}  ${profile.name}" +
                        if (active?.id == profile.id) "  ·  ${stringResource(R.string.dial_profiles_current)}" else ""
                )
                SettingRow(
                    label = stringResource(R.string.dial_profiles_kids),
                    value = if (profile.kids) on else off,
                    onClick = { viewModel.setKids(profile.id, !profile.kids) },
                )
                if (profile.kids) {
                    SettingRow(
                        label = stringResource(R.string.dial_profiles_timer),
                        value = minutesLabel(profile.kidsMinutes),
                        onClick = { viewModel.setMinutes(profile.id, TIMER_OPTIONS.nextAfter(profile.kidsMinutes)) },
                    )
                }
                SettingRow(
                    label = stringResource(R.string.dial_profiles_pin),
                    value = stringResource(if (profile.hasPin) R.string.dial_profiles_pin_set else R.string.dial_profiles_pin_none),
                    onClick = {
                        editingPin = if (editingPin == profile.id) null else profile.id
                        pinDraft = ""
                    },
                )
                if (editingPin == profile.id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(360.dp)) {
                            DialTextField(
                                label = stringResource(R.string.dial_profiles_pin_new),
                                value = pinDraft,
                                onValueChange = { pinDraft = it.filter { c -> c.isDigit() }.take(8) },
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done,
                                readOnly = false,
                                secret = true,
                                onDone = {
                                    if (pinDraft.length in ProfileStore.PIN_LENGTH) {
                                        viewModel.setPin(profile.id, pinDraft)
                                        editingPin = null
                                    }
                                },
                            )
                        }
                        if (profile.hasPin) {
                            TvActionButton(
                                text = stringResource(R.string.dial_profiles_pin_remove),
                                icon = Icons.Rounded.Delete,
                                onClick = {
                                    viewModel.setPin(profile.id, null)
                                    editingPin = null
                                },
                            )
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                    items(PROFILE_FACES, key = { "${profile.id}-$it" }) { face ->
                        TvActionButton(
                            text = face,
                            icon = Icons.Rounded.Check,
                            selected = face == profile.face,
                            showTextWhenUnfocused = true,
                            onClick = { viewModel.setFace(profile.id, face) },
                        )
                    }
                }
                if (profiles.size > 1) {
                    TvActionButton(
                        text = stringResource(R.string.dial_profiles_remove),
                        icon = Icons.Rounded.Delete,
                        onClick = { viewModel.remove(profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun minutesLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.dial_profiles_no_limit) else stringResource(R.string.dial_profiles_minutes, minutes)

private val TIMER_OPTIONS = listOf(0, 30, 60, 90, 120, 180, 240)

/** The kids timer ran out: playback has stopped; a grown-up picks another profile. */
@Composable
fun TimeUpCard(onSwitchProfile: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .width(480.dp)
            .background(TvColors.Background.copy(alpha = 0.96f), HudShape)
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.dial_profiles_time_up),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
        Text(
            text = stringResource(R.string.dial_profiles_time_up_hint),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 15.sp,
        )
        TvActionButton(
            text = stringResource(R.string.dial_profiles_switch),
            icon = Icons.Rounded.SwitchAccount,
            focusRequester = focus,
            onClick = onSwitchProfile,
        )
    }
}
