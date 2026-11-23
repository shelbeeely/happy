package com.slopus.happy.ui.inference

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.inference.CatalogState
import com.slopus.happy.inference.ConnectionTest
import com.slopus.happy.inference.HostCredentials
import com.slopus.happy.inference.InferenceCapability
import com.slopus.happy.inference.StoreCredentialResult
import com.slopus.happy.model.MachineChoice
import com.slopus.happy.ui.components.Alert
import com.slopus.happy.ui.components.AlertButton
import com.slopus.happy.ui.components.HappyAlert
import com.slopus.happy.ui.components.Prompt
import com.slopus.happy.ui.components.PromptInputType
import com.slopus.happy.ui.components.PromptModal
import com.slopus.happy.ui.localai.LocalAiSettingsRoute
import com.slopus.happy.ui.page
import com.slopus.happy.ui.settings.rememberMachineChoices
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object ProvidersRoute
@Serializable data class ProfileEditorRoute(val id: String? = null)
@Serializable data object ModelBrowserRoute

private const val PICKED_MODEL = "inference.pickedModel"

fun NavGraphBuilder.providerRoutes(graph: AppGraph, nav: NavHostController) {
    page<ProvidersRoute> { Providers(graph, nav) }
    page<ProfileEditorRoute> { entry ->
        val id = entry.toRoute<ProfileEditorRoute>().id
        val picked by entry.savedStateHandle.getStateFlow<String?>(PICKED_MODEL, null).collectAsStateWithLifecycle()
        ProfileEditor(graph, nav, id, picked) { entry.savedStateHandle[PICKED_MODEL] = null }
    }
    page<ModelBrowserRoute> {
        val settings by graph.settings.settings.collectAsStateWithLifecycle()
        val state by graph.openRouterCatalog.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { graph.openRouterCatalog.refresh() }
        ModelBrowserScreen(
            state = state,
            favorites = settings.inference.favorites,
            onToggleFavorite = { model -> graph.settings.change { it.withInference(it.inference.toggleFavorite(model)) } },
            onPick = { model ->
                nav.previousBackStackEntry?.savedStateHandle?.set(PICKED_MODEL, model.id)
                nav.popBackStack()
            },
            onRetry = { scope.launch { graph.openRouterCatalog.refresh(force = true) } },
            onBack = { nav.popBackStack() },
        )
    }
}

/** The account's Happy CLI computers with their OpenRouter capability. */
@Composable
private fun rememberProviderMachines(graph: AppGraph, credentials: Map<String, HostCredentials>, checking: Set<String>): List<ProviderMachine> {
    val choices by rememberMachineChoices(graph)
    return choices.mapNotNull { choice: MachineChoice ->
        val machine = choice.happyMachine ?: return@mapNotNull null
        ProviderMachine(
            id = machine.id,
            name = choice.name,
            online = machine.isOnline,
            capable = InferenceCapability.from(machine.metadata?.raw)?.openRouter == true,
            credentials = credentials[machine.id],
            checking = machine.id in checking,
        )
    }
}

@Composable
private fun Providers(graph: AppGraph, nav: NavHostController) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val account by graph.account.collectAsStateWithLifecycle()
    val credentials = remember { mutableStateMapOf<String, HostCredentials>() }
    val checking = remember { mutableStateMapOf<String, Boolean>() }
    val machines = rememberProviderMachines(graph, credentials, checking.keys)
    val scope = rememberCoroutineScope()
    var storingOn by remember { mutableStateOf<ProviderMachine?>(null) }
    var alert by remember { mutableStateOf<Alert?>(null) }
    val ok = stringResource(R.string.common_ok)
    val storedTitle = stringResource(R.string.inference_key_stored_title)
    val storedMessage = stringResource(R.string.inference_key_stored_message)
    val errorTitle = stringResource(R.string.common_error)
    val resources = androidx.compose.ui.platform.LocalResources.current

    fun check(machine: ProviderMachine) {
        val ops = account?.machineOps ?: return
        if (checking[machine.id] == true) return
        checking[machine.id] = true
        scope.launch {
            try {
                ops.inferenceStatus(machine.id)?.let { credentials[machine.id] = it }
            } finally {
                checking.remove(machine.id)
            }
        }
    }

    // Ask each capable online computer once when the page opens.
    LaunchedEffect(machines.filter { it.online && it.capable }.map { it.id }) {
        machines.filter { it.online && it.capable && it.id !in credentials }.forEach(::check)
    }

    ProvidersScreen(
        profiles = settings.inference.profiles,
        machines = machines,
        onAdd = { nav.navigate(ProfileEditorRoute()) },
        onEdit = { nav.navigate(ProfileEditorRoute(it.id)) },
        onStoreKey = { storingOn = it },
        onCheck = ::check,
        onLocalAi = { nav.navigate(LocalAiSettingsRoute) },
        onBack = { nav.popBackStack() },
    )

    storingOn?.let { machine ->
        PromptModal(
            Prompt(
                title = stringResource(R.string.inference_store_key_title, machine.name),
                message = stringResource(R.string.inference_store_key_message),
                placeholder = "sk-or-…",
                confirmText = stringResource(R.string.inference_store),
                inputType = PromptInputType.SecureText,
                allowBlank = false,
                onResult = { key ->
                    val ops = account?.machineOps
                    if (key != null && ops != null) {
                        scope.launch {
                            alert = when (val result = ops.storeInferenceCredential(machine.id, key)) {
                                StoreCredentialResult.Stored -> {
                                    check(machine.copy(checking = false))
                                    Alert(storedTitle, storedMessage, listOf(AlertButton(ok)))
                                }
                                is StoreCredentialResult.Failed -> Alert(
                                    errorTitle,
                                    listOfNotNull(resources.getString(problemText(result.problem)), result.detail).joinToString("\n"),
                                    listOf(AlertButton(ok)),
                                )
                            }
                        }
                    }
                },
            ),
            onDismiss = { storingOn = null },
        )
    }
    alert?.let { HappyAlert(it, onDismiss = { alert = null }) }
}

@Composable
private fun ProfileEditor(graph: AppGraph, nav: NavHostController, id: String?, picked: String?, onPickedConsumed: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val account by graph.account.collectAsStateWithLifecycle()
    val catalog by graph.openRouterCatalog.state.collectAsStateWithLifecycle()
    val machines = rememberProviderMachines(graph, emptyMap(), emptySet())
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ConnectionTest?>(null) }
    LaunchedEffect(Unit) { graph.openRouterCatalog.refresh() }
    val initial = settings.inference.profile(id)
    ProfileEditorScreen(
        initial = initial,
        pickedModel = picked,
        onPickedModelConsumed = onPickedConsumed,
        catalogModel = { slug -> (catalog as? CatalogState.Loaded)?.models?.find { it.id == slug } },
        testMachines = machines,
        testing = testing,
        testResult = result,
        onBrowseModels = { nav.navigate(ModelBrowserRoute) },
        onTest = { machine, profile ->
            val ops = account?.machineOps ?: return@ProfileEditorScreen
            testing = true
            result = null
            scope.launch {
                try {
                    result = ops.inferenceTest(machine.id, profile)
                } finally {
                    testing = false
                }
            }
        },
        onSave = { profile ->
            graph.settings.change { it.withInference(it.inference.upsert(profile)) }
            nav.popBackStack()
        },
        onDelete = { profile ->
            graph.settings.change { it.withInference(it.inference.remove(profile.id)) }
            nav.popBackStack()
        },
        onBack = { nav.popBackStack() },
    )
}
