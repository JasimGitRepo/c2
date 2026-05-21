package com.c2c.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.c2c.CoreViewModel
import com.c2c.ServerCore
import com.c2c.data.local.CommandEntity
import com.c2c.ui.theme.*
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun MainScreen(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    var selectedTab by remember { mutableStateOf(0) }
    
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0x990A0A0F)) {
                NavigationBarItem(
                    selected = selectedTab == 0, 
                    onClick = { selectedTab = 0 }, 
                    icon = { Icon(Icons.Rounded.Search, null) }, 
                    label = { Text("HUB") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1, 
                    onClick = { selectedTab = 1 }, 
                    icon = { Icon(Icons.Rounded.Dns, null) }, 
                    label = { Text("SERVER") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedTab == 0) {
                CommandHubScreen(viewModel = viewModel)
            } else {
                ServerPagerScreen(viewModel = viewModel, eglContext = eglContext)
            }
        }
    }
}

@Composable
fun CommandHubScreen(viewModel: CoreViewModel) {
    val commands by viewModel.commands.collectAsState()
    val pendingCommands by viewModel.pendingCommands.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var commandToEdit by remember { mutableStateOf<CommandEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredCommands = commands.filter { 
        it.label.contains(searchQuery, ignoreCase = true) 
    }

    val quickCommands = filteredCommands.filter { it.category == "Quick" }.take(6)
    val softKeyCommands = filteredCommands.filter { it.category == "SoftKey" }.take(3)
    val gridCommands = filteredCommands.filter { it.category != "Quick" && it.category != "SoftKey" }
    val groupedGridCommands = gridCommands.groupBy { it.category }

    if (showAddDialog || commandToEdit != null) {
        CommandEditorDialog(
            command = commandToEdit,
            onDismiss = { showAddDialog = false; commandToEdit = null },
            onSave = { entity -> 
                viewModel.saveCommand(entity)
                showAddDialog = false
                commandToEdit = null
            },
            onDelete = {
                commandToEdit?.let { viewModel.deleteCommand(it) }
                showAddDialog = false
                commandToEdit = null
            }
        )
    }

    if (showSettingsDialog) {
        CoreSettingsDialog(
            viewModel = viewModel, 
            onDismiss = { showSettingsDialog = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.activateKillSwitch() },
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Warning, null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("KILL SWITCH", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            
            IconButton(
                onClick = { viewModel.isVerboseMode = !viewModel.isVerboseMode },
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(if (viewModel.isVerboseMode) ActionBlue else GlassSurface)
            ) {
                Icon(if (viewModel.isVerboseMode) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff, contentDescription = "Verbose", tint = if (viewModel.isVerboseMode) Color.White else ActionBlue)
            }
            Spacer(modifier = Modifier.width(8.dp))
            
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(GlassSurface)
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = ActionBlue)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GlassSurface).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            quickCommands.forEach { cmd ->
                val uiKey = "qa_${cmd.id}"
                val isPending = pendingCommands.contains(uiKey)
                val isToggled = viewModel.toggleStates[uiKey] ?: false

                InteractiveIconButton(
                    icon = resolveIcon(if (isToggled && cmd.isToggle) cmd.toggledCmd else cmd.cmd, if (isToggled && cmd.isToggle) cmd.toggledIcon() else cmd.icon),
                    isPending = isPending,
                    iconColor = TextPrimary,
                    onClick = {
                        val actualCmd = if (isToggled && cmd.isToggle) cmd.toggledCmd else cmd.cmd
                        val actualArg = if (isToggled && cmd.isToggle) cmd.toggledArg else cmd.defaultArg
                        viewModel.enqueueCommand(actualCmd, actualArg, uiKey, isLive = false)
                        if (cmd.isToggle) viewModel.toggleStates[uiKey] = !isToggled
                    },
                    onLongClick = { commandToEdit = cmd }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.Black).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            softKeyCommands.forEach { cmd ->
                val uiKey = "sk_${cmd.id}"
                val isPending = pendingCommands.contains(uiKey)
                
                InteractiveIconButton(
                    icon = resolveIcon(cmd.cmd, cmd.icon),
                    isPending = isPending,
                    iconColor = Color.White,
                    onClick = { viewModel.enqueueCommand(cmd.cmd, cmd.defaultArg, uiKey, isLive = true) },
                    onLongClick = { commandToEdit = cmd }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("Search directives...") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = GlassSurface, unfocusedContainerColor = GlassSurface)
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            val categories = groupedGridCommands.keys.toList().sorted()
            
            for (categoryIndex in categories.indices) {
                val categoryName = categories[categoryIndex]
                val cmdsInCategory = groupedGridCommands[categoryName] ?: emptyList()
                val chunkedRows = cmdsInCategory.chunked(2)

                item {
                    Text(
                        text = categoryName, 
                        style = MaterialTheme.typography.titleSmall, 
                        color = PremiumTeal, 
                        modifier = Modifier.fillMaxWidth().background(SoulBackground.copy(alpha = 0.95f)).padding(vertical = 8.dp)
                    )
                }

                items(count = chunkedRows.size) { rowIndex ->
                    val rowCommands = chunkedRows[rowIndex]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (cmdIndex in 0..1) {
                            if (cmdIndex < rowCommands.size) {
                                val cmd = rowCommands[cmdIndex]
                                val uiKey = cmd.id.toString()
                                val isPending = pendingCommands.contains(uiKey)
                                val isToggled = viewModel.toggleStates[uiKey] ?: false

                                CommandCard(
                                    cmd = cmd,
                                    isPending = isPending,
                                    isToggled = isToggled,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val actualCmd = if (isToggled && cmd.isToggle) cmd.toggledCmd else cmd.cmd
                                        val actualArg = if (isToggled && cmd.isToggle) cmd.toggledArg else cmd.defaultArg
                                        viewModel.enqueueCommand(actualCmd, actualArg, uiKey, isLive = false)
                                        if (cmd.isToggle) viewModel.toggleStates[uiKey] = !isToggled
                                    },
                                    onLongClick = { commandToEdit = cmd }
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f)) 
                            }
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        FloatingActionButton(onClick = { showAddDialog = true }, modifier = Modifier.padding(24.dp), containerColor = ActionBlue) { 
            Icon(Icons.Rounded.Add, null) 
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InteractiveIconButton(
    icon: ImageVector,
    isPending: Boolean,
    iconColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(if (isPending) 0.4f else 1f)
            .combinedClickable(
                enabled = !isPending,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isPending) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = ActionBlue, strokeWidth = 2.dp)
        } else {
            Icon(icon, null, tint = iconColor)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandCard(
    cmd: CommandEntity,
    isPending: Boolean,
    isToggled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(64.dp)
            .alpha(if (isPending) 0.4f else 1f)
            .combinedClickable(
                enabled = !isPending,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = GlassSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPending) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ActionBlue, strokeWidth = 2.dp)
            } else {
                val currentIconStr = if (isToggled && cmd.isToggle) cmd.toggledIcon() else cmd.icon
                val currentCmdStr = if (isToggled && cmd.isToggle) cmd.toggledCmd else cmd.cmd
                Icon(resolveIcon(currentCmdStr, currentIconStr), null, tint = ActionBlue, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = if (isToggled && cmd.isToggle) cmd.toggledLabel else cmd.label, 
                    color = TextPrimary, 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold, 
                    maxLines = 1
                )
                val argText = if (isToggled && cmd.isToggle) cmd.toggledArg else cmd.defaultArg
                if (argText.isNotBlank()) {
                    Text(
                        text = argText, 
                        color = PremiumTeal, 
                        fontSize = 9.sp, 
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandEditorDialog(command: CommandEntity?, onDismiss: () -> Unit, onSave: (CommandEntity) -> Unit, onDelete: () -> Unit) {
    var label by remember { mutableStateOf(command?.label ?: "") }
    var cmd by remember { mutableStateOf(command?.cmd ?: "") }
    var arg by remember { mutableStateOf(command?.defaultArg ?: "") }
    var icon by remember { mutableStateOf(command?.icon ?: "code") }
    var category by remember { mutableStateOf(command?.category ?: "System") }
    
    var isToggle by remember { mutableStateOf(command?.isToggle ?: false) }
    var toggledLabel by remember { mutableStateOf(command?.toggledLabel ?: "") }
    var toggledCmd by remember { mutableStateOf(command?.toggledCmd ?: "") }
    var toggledArg by remember { mutableStateOf(command?.toggledArg ?: "") }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C242F)), 
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                item { Text(if (command == null) "New Command" else "Edit Command", color = ActionBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                item { Spacer(modifier = Modifier.height(16.dp)) }

                item { OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = cmd, onValueChange = { cmd = it }, label = { Text("Command (e.g. loc)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = arg, onValueChange = { arg = it }, label = { Text("Argument (Optional)") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Icon string") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth()) }

                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isToggle, onCheckedChange = { isToggle = it }, colors = CheckboxDefaults.colors(checkedColor = ActionBlue))
                        Text("Toggle Command", color = TextPrimary)
                    }
                }

                if (isToggle) {
                    item { OutlinedTextField(value = toggledLabel, onValueChange = { toggledLabel = it }, label = { Text("Toggled Label") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(value = toggledCmd, onValueChange = { toggledCmd = it }, label = { Text("Toggled Command") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(value = toggledArg, onValueChange = { toggledArg = it }, label = { Text("Toggled Argument (Optional)") }, modifier = Modifier.fillMaxWidth()) }
                }
                
                item { Spacer(modifier = Modifier.height(16.dp)) }
                item { 
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        if (command != null) {
                            TextButton(onClick = onDelete) { Text("DELETE", color = ErrorRed) }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(onClick = onDismiss) { Text("CANCEL", color = TextSecondary) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { 
                            val updatedCommand = CommandEntity(
                                id = command?.id ?: 0, 
                                label = label, cmd = cmd, defaultArg = arg, icon = icon, category = category,
                                isToggle = isToggle, toggledLabel = toggledLabel, toggledCmd = toggledCmd, toggledArg = toggledArg
                            )
                            onSave(updatedCommand) 
                        }, colors = ButtonDefaults.buttonColors(containerColor = ActionBlue)) { Text("SAVE") }
                    }
                }
            }
        }
    }
}

@Composable
fun CoreSettingsDialog(viewModel: CoreViewModel, onDismiss: () -> Unit) {
    var settings by remember { mutableStateOf(viewModel.getSettings()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C242F)), 
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Connection Config", color = ActionBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(value = settings.ntfyUrl, onValueChange = { settings = settings.copy(ntfyUrl = it) }, label = { Text("Ntfy URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = settings.clientTopic, onValueChange = { settings = settings.copy(clientTopic = it) }, label = { Text("Client Topic (TX)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = settings.serverTopic, onValueChange = { settings = settings.copy(serverTopic = it) }, label = { Text("Server Topic (RX)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = settings.serverIp, onValueChange = { settings = settings.copy(serverIp = it) }, label = { Text("Ktor Bind IP") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = settings.port, onValueChange = { settings = settings.copy(port = it) }, label = { Text("Ktor Port") }, modifier = Modifier.fillMaxWidth())
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("CANCEL", color = TextSecondary) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { viewModel.saveSettings(settings); onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = ActionBlue)) {
                        Text("SAVE")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerPagerScreen(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    Column {
        TabRow(
            selectedTabIndex = pagerState.currentPage, 
            containerColor = Color.Transparent, 
            contentColor = PremiumTeal, 
            indicator = { tabPositions -> 
                TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]), color = PremiumTeal) 
            }
        ) {
            Tab(selected = pagerState.currentPage == 0, onClick = { }, text = { Text("TERMINAL") })
            Tab(selected = pagerState.currentPage == 1, onClick = { }, text = { Text("WEBRTC") })
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when(page) {
                0 -> TerminalPage(viewModel, LocalContext.current)
                1 -> LiveWebRtcHub(viewModel, eglContext)
            }
        }
    }
}

@Composable
fun TerminalPage(viewModel: CoreViewModel, context: Context) {
    val isRunning by ServerCore.isRunningFlow.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.serverLogs.size) { if(viewModel.serverLogs.isNotEmpty()) listState.animateScrollToItem(viewModel.serverLogs.size - 1) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val btnColor = if (isRunning) ErrorRed else ActionBlue
        Button(
            onClick = { viewModel.toggleServer(context) },
            colors = ButtonDefaults.buttonColors(containerColor = btnColor),
            modifier = Modifier.height(56.dp).fillMaxWidth()
        ) {
            Text(if (isRunning) "TERMINATE NODE" else "INITIALIZE NODE")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("System Event Log", color = TextSecondary, fontSize = 14.sp)
            IconButton(onClick = { viewModel.serverLogs.clear() }) { Icon(Icons.Rounded.DeleteOutline, null, tint = TextSecondary) }
        }
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top=8.dp).border(1.dp, GlassBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = GlassSurface)
        ) {
            LazyColumn(state = listState, modifier = Modifier.padding(12.dp)) { 
                items(count = viewModel.serverLogs.size) { index -> 
                    val log = viewModel.serverLogs[index]
                    val color = if (log.contains("ERROR") || log.contains("FAIL") || log.contains("SEVERED") || log.contains("DROP") || log.contains("ABORTED") || log.contains("Timeout")) PremiumRose 
                                else if (log.contains("SUCCESS") || log.contains("ESTABLISHED") || log.contains("STARTED") || log.contains("Secured")) PremiumTeal 
                                else if (log.contains("QUEUED") || log.contains("SENT") || log.contains("CLIENT") || log.contains("Initiating")) ActionBlue
                                else TextPrimary
                    Text(log, color = color, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) 
                } 
            }
        }
    }
}

@Composable
fun LiveWebRtcHub(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        
        Button(
            onClick = { viewModel.toggleWebRtc() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !viewModel.isWebRtcConnecting,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (viewModel.isWebRtcConnected) ErrorRed else ActionBlue
            )
        ) {
            if (viewModel.isWebRtcConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("ESTABLISHING LINK...")
            } else {
                Text(if (viewModel.isWebRtcConnected) "TERMINATE WEBRTC LINK" else "ESTABLISH WEBRTC LINK")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.toggleAudioStream("call") },
                modifier = Modifier.weight(1f),
                enabled = viewModel.isWebRtcConnected && !viewModel.isWebRtcConnecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.activeAudio == "call") SuccessGreen else GlassSurface,
                    contentColor = TextPrimary,
                    disabledContainerColor = GlassSurface.copy(alpha = 0.5f),
                    disabledContentColor = TextPrimary.copy(alpha = 0.5f)
                )
            ) { Text("CALL", fontSize = 11.sp) }

            Button(
                onClick = { viewModel.toggleAudioStream("broadcast") },
                modifier = Modifier.weight(1f),
                enabled = viewModel.isWebRtcConnected && !viewModel.isWebRtcConnecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.activeAudio == "broadcast") SuccessGreen else GlassSurface,
                    contentColor = TextPrimary,
                    disabledContainerColor = GlassSurface.copy(alpha = 0.5f),
                    disabledContentColor = TextPrimary.copy(alpha = 0.5f)
                )
            ) { Text("BROADCAST", fontSize = 11.sp) }

            Button(
                onClick = { viewModel.toggleAudioStream("receive") },
                modifier = Modifier.weight(1f),
                enabled = viewModel.isWebRtcConnected && !viewModel.isWebRtcConnecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.activeAudio == "receive") SuccessGreen else GlassSurface,
                    contentColor = TextPrimary,
                    disabledContainerColor = GlassSurface.copy(alpha = 0.5f),
                    disabledContentColor = TextPrimary.copy(alpha = 0.5f)
                )
            ) { Text("LISTEN", fontSize = 11.sp) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val isVideoActive = viewModel.activeVideo == "cam1" || viewModel.activeVideo == "cam2"
            val isScreenActive = viewModel.activeVideo == "screen"

            Button(
                onClick = { viewModel.toggleVideoStream("cam1") },
                modifier = Modifier.weight(1f),
                enabled = viewModel.isWebRtcConnected && !viewModel.isWebRtcConnecting && !isScreenActive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.activeVideo == "cam1") SuccessGreen else GlassSurface,
                    contentColor = TextPrimary,
                    disabledContainerColor = GlassSurface.copy(alpha = 0.5f),
                    disabledContentColor = TextPrimary.copy(alpha = 0.5f)
                )
            ) { Text("CAM F", fontSize = 11.sp) }

            Button(
                onClick = { viewModel.toggleVideoStream("cam2") },
                modifier = Modifier.weight(1f),
                enabled = viewModel.isWebRtcConnected && !viewModel.isWebRtcConnecting && !isScreenActive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.activeVideo == "cam2") SuccessGreen else GlassSurface,
                    contentColor = TextPrimary,
                    disabledContainerColor = GlassSurface.copy(alpha = 0.5f),
                    disabledContentColor = TextPrimary.copy(alpha = 0.5f)
                )
            ) { Text("CAM B", fontSize = 11.sp) }

            Button(
                onClick = { viewModel.toggleVideoStream("screen") },
                modifier = Modifier.weight(1f),
                enabled = viewModel.isWebRtcConnected && !viewModel.isWebRtcConnecting && !isVideoActive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScreenActive) SuccessGreen else GlassSurface,
                    contentColor = TextPrimary,
                    disabledContainerColor = GlassSurface.copy(alpha = 0.5f),
                    disabledContentColor = TextPrimary.copy(alpha = 0.5f)
                )
            ) { Text("SCREEN", fontSize = 11.sp) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black).clip(RoundedCornerShape(8.dp))) {
            if (viewModel.remoteVideoTrack != null) {
                var attachedTrack by remember { mutableStateOf<VideoTrack?>(null) }
                
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(eglContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                            setEnableHardwareScaler(true)
                            setZOrderMediaOverlay(true)
                        }
                    },
                    update = { view -> 
                        if (attachedTrack != viewModel.remoteVideoTrack) {
                            try { attachedTrack?.removeSink(view) } catch (e: Exception) { Log.e("ERROR_TO_DEBUG", "Remove sink error", e) }
                            try { 
                                viewModel.remoteVideoTrack?.addSink(view) 
                                Log.e("ERROR_TO_DEBUG", "Sink attached to VideoTrack Successfully")
                            } catch (e: Exception) { Log.e("ERROR_TO_DEBUG", "Add sink error", e) }
                            attachedTrack = viewModel.remoteVideoTrack
                        }
                    },
                    onRelease = { view -> 
                        try { attachedTrack?.removeSink(view) } catch (e: Exception) {}
                        view.release() 
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("Awaiting WebRTC Video Track...", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

fun CommandEntity.toggledIcon(): String {
    return when(this.cmd) {
        "flash" -> "flashlight_off" 
        "vol" -> "volume_off" 
        "icon_hide" -> "visibility" 
        "toggle_wifi" -> "wifi_off" 
        "toggle_hotspot" -> "hotspot_off" 
        "call" -> "phone_disabled" 
        "halt_workflow" -> "play_arrow" 
        else -> this.icon 
    }
}

fun resolveIcon(cmd: String, overrideIconName: String): ImageVector {
    if (overrideIconName.isNotBlank() && overrideIconName != "code") {
        return getIconByName(overrideIconName)
    }
    return when(cmd) {
        "btn_recents" -> Icons.Rounded.Menu
        "btn_home" -> Icons.Rounded.Circle
        "btn_back" -> Icons.Rounded.ArrowBackIosNew
        else -> Icons.Rounded.Code
    }
}

fun getIconByName(name: String): ImageVector {
    return when(name) {
        "flash", "flashlight", "flashlight_on" -> Icons.Rounded.FlashlightOn
        "flashlight_off" -> Icons.Rounded.FlashlightOff 
        "screen", "light_mode" -> Icons.Rounded.LightMode
        "loc", "location", "location_on" -> Icons.Rounded.LocationOn
        "wifi" -> Icons.Rounded.Wifi
        "wifi_off" -> Icons.Rounded.WifiOff 
        "bluetooth" -> Icons.Rounded.Bluetooth
        "hotspot_off" -> Icons.Rounded.WifiOff 
        "folder" -> Icons.Rounded.FolderOpen
        "radar" -> Icons.Rounded.Radar
        "camera_front" -> Icons.Rounded.CameraFront
        "camera_rear" -> Icons.Rounded.CameraRear
        "videocam_off" -> Icons.Rounded.VideocamOff
        "mic" -> Icons.Rounded.Mic
        "mic_off" -> Icons.Rounded.MicOff
        "info" -> Icons.Rounded.Info
        "description", "log" -> Icons.Rounded.Description
        "delete_sweep", "clear" -> Icons.Rounded.DeleteSweep
        "power_settings_new", "power" -> Icons.Rounded.PowerSettingsNew
        "router", "server" -> Icons.Rounded.Router
        "volume_up", "vol" -> Icons.Rounded.VolumeUp
        "volume_off" -> Icons.Rounded.VolumeOff 
        "screenshot_monitor" -> Icons.Rounded.ScreenshotMonitor
        "track_changes" -> Icons.Rounded.TrackChanges
        "play_arrow" -> Icons.Rounded.PlayArrow
        "account_tree" -> Icons.Rounded.AccountTree
        "upload_file" -> Icons.Rounded.UploadFile
        "system_update" -> Icons.Rounded.SystemUpdate
        "visibility_off" -> Icons.Rounded.VisibilityOff
        "visibility" -> Icons.Rounded.Visibility 
        "phone" -> Icons.Rounded.Phone
        "phone_disabled" -> Icons.Rounded.PhoneDisabled
        "cloud_download" -> Icons.Rounded.CloudDownload
        "network_wifi" -> Icons.Rounded.NetworkWifi 
        "stop" -> Icons.Rounded.Stop 
        "menu" -> Icons.Rounded.Menu 
        "circle" -> Icons.Rounded.Circle 
        "arrow_back_ios_new" -> Icons.Rounded.ArrowBackIosNew 
        "notifications_active" -> Icons.Rounded.NotificationsActive
        "notifications_off" -> Icons.Rounded.NotificationsOff
        "settings" -> Icons.Rounded.Settings
        else -> Icons.Rounded.Code
    }
}