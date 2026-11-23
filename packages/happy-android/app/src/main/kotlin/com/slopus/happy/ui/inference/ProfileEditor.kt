package com.slopus.happy.ui.inference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.slopus.happy.R
import com.slopus.happy.inference.CatalogState
import com.slopus.happy.inference.ConnectionTest
import com.slopus.happy.inference.CredentialRefs
import com.slopus.happy.inference.InferenceProfile
import com.slopus.happy.inference.OpenRouterCatalog
import com.slopus.happy.inference.OpenRouterModel
import com.slopus.happy.inference.ProfileProblem
import com.slopus.happy.inference.RoutingVariant
import com.slopus.happy.inference.ServerTool
import com.slopus.happy.ui.components.Icon
import com.slopus.happy.ui.components.Ionicon
import com.slopus.happy.ui.components.Item
import com.slopus.happy.ui.components.ItemGroup
import com.slopus.happy.ui.components.ItemList
import com.slopus.happy.ui.components.ItemSpec
import com.slopus.happy.ui.components.HappySwitch
import com.slopus.happy.ui.components.Screen
import com.slopus.happy.ui.components.StackHeader
import com.slopus.happy.ui.settings.ItemIcon
import com.slopus.happy.ui.theme.HappyTheme
import java.util.UUID

/**
 * One profile's editor. Every field is plain text the person can see; the
 * key is not among them (it stays on the computer). "Test connection" asks
 * a chosen computer to check the key, the model and the server tools from
 * where the session will actually run.
 */
@Composable
fun ProfileEditorScreen(
    initial: InferenceProfile?,
    pickedModel: String?,
    onPickedModelConsumed: () -> Unit,
    catalogModel: (String) -> OpenRouterModel?,
    testMachines: List<ProviderMachine>,
    testing: Boolean,
    testResult: ConnectionTest?,
    onBrowseModels: () -> Unit,
    onTest: (ProviderMachine, InferenceProfile) -> Unit,
    onSave: (InferenceProfile) -> Unit,
    onDelete: ((InferenceProfile) -> Unit)?,
    onBack: () -> Unit,
) {
    val colors = HappyTheme.colors
    val id = rememberSaveable { initial?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(initial?.model.orEmpty()) }
    var small by rememberSaveable { mutableStateOf(initial?.smallFastModel.orEmpty()) }
    var credential by rememberSaveable { mutableStateOf(initial?.credentialRef ?: CredentialRefs.HAPPY_STORED) }
    var envName by rememberSaveable { mutableStateOf(initial?.credentialRef?.let(CredentialRefs::envName) ?: "OPENROUTER_API_KEY") }
    var routing by rememberSaveable { mutableStateOf(initial?.routingVariant?.wire.orEmpty()) }
    var tools by rememberSaveable { mutableStateOf(initial?.serverTools.orEmpty().joinToString(",") { it.wire }) }
    var budget by rememberSaveable { mutableStateOf(initial?.budgetUsd?.toString().orEmpty()) }
    var warnAt by rememberSaveable { mutableStateOf(initial?.warnAtUsd?.toString().orEmpty()) }
    var testMachineId by rememberSaveable { mutableStateOf(testMachines.firstOrNull { it.online && it.capable }?.id) }

    if (pickedModel != null) {
        model = pickedModel
        onPickedModelConsumed()
    }

    val selectedTools = tools.split(',').mapNotNull(ServerTool::fromWire).toSet()
    val draft = InferenceProfile(
        id = id,
        name = name.trim(),
        credentialRef = if (credential == CredentialRefs.HAPPY_STORED) credential else "env:${envName.trim()}",
        model = model.trim(),
        smallFastModel = small.trim().ifEmpty { null },
        routingVariant = RoutingVariant.fromWire(routing),
        serverTools = ServerTool.entries.filter { it in selectedTools },
        budgetUsd = budget.trim().toDoubleOrNull(),
        warnAtUsd = warnAt.trim().toDoubleOrNull(),
    )
    val problems = draft.problems() +
        (if (budget.isNotBlank() && budget.trim().toDoubleOrNull() == null) setOf(ProfileProblem.Budget) else emptySet()) +
        (if (warnAt.isNotBlank() && warnAt.trim().toDoubleOrNull() == null) setOf(ProfileProblem.Budget) else emptySet())
    val info = catalogModel(model.trim().substringBefore(':'))

    Screen(
        header = { StackHeader(stringResource(if (initial == null) R.string.inference_new_profile else R.string.inference_edit_profile), onBack) },
        avoidKeyboard = true,
    ) {
        ItemList {
            ItemGroup {
                FieldRow(stringResource(R.string.inference_profile_name), name, { name = it }, placeholder = stringResource(R.string.inference_profile_name_placeholder), error = ProfileProblem.Name in problems && name.isNotEmpty())
            }
            ItemGroup(title = stringResource(R.string.inference_model), footer = stringResource(R.string.inference_model_footer)) {
                FieldRow(stringResource(R.string.inference_model_slug), model, { model = it }, placeholder = "anthropic/claude-sonnet-5.5", error = ProfileProblem.Model in problems && model.isNotEmpty())
                Item(
                    title = stringResource(R.string.inference_browse_models),
                    icon = { ItemIcon(Ionicon.SearchOutline, colors.textLink) },
                    onClick = onBrowseModels,
                )
                if (info != null) {
                    Item(title = info.name, subtitle = modelFacts(info), subtitleLines = 3, showChevron = false)
                    if (!info.supportsTools) {
                        Item(
                            title = stringResource(R.string.inference_problem_model_no_tools),
                            icon = { ItemIcon(Ionicon.WarningOutline, colors.textDestructive) },
                            showChevron = false,
                        )
                    }
                }
                FieldRow(stringResource(R.string.inference_small_model), small, { small = it }, placeholder = stringResource(R.string.inference_small_model_placeholder))
            }
            ItemGroup(title = stringResource(R.string.inference_routing), footer = stringResource(R.string.inference_routing_footer)) {
                Choice(stringResource(R.string.inference_routing_default), routing.isEmpty()) { routing = "" }
                Choice(stringResource(R.string.inference_routing_nitro), routing == RoutingVariant.Nitro.wire) { routing = RoutingVariant.Nitro.wire }
                Choice(stringResource(R.string.inference_routing_floor), routing == RoutingVariant.Floor.wire) { routing = RoutingVariant.Floor.wire }
            }
            ItemGroup(title = stringResource(R.string.inference_server_tools), footer = stringResource(R.string.inference_server_tools_footer)) {
                for (tool in ServerTool.entries) {
                    val label = stringResource(serverToolName(tool))
                    Item(
                        title = label,
                        rightElement = {
                            HappySwitch(
                                checked = tool in selectedTools,
                                onCheckedChange = { on -> tools = (if (on) selectedTools + tool else selectedTools - tool).joinToString(",") { it.wire } },
                                label = label,
                            )
                        },
                        showChevron = false,
                    )
                }
            }
            ItemGroup(title = stringResource(R.string.inference_credential), footer = stringResource(R.string.inference_credential_footer)) {
                Choice(stringResource(R.string.inference_credential_stored), credential == CredentialRefs.HAPPY_STORED) { credential = CredentialRefs.HAPPY_STORED }
                Choice(stringResource(R.string.inference_credential_env), credential != CredentialRefs.HAPPY_STORED) { credential = "env" }
                if (credential != CredentialRefs.HAPPY_STORED) {
                    FieldRow(stringResource(R.string.inference_env_name), envName, { envName = it.filter { c -> c.isLetterOrDigit() || c == '_' } }, error = ProfileProblem.Credential in problems)
                }
            }
            ItemGroup(title = stringResource(R.string.inference_budget), footer = stringResource(R.string.inference_budget_footer)) {
                FieldRow(stringResource(R.string.inference_warn_at), warnAt, { warnAt = it }, placeholder = "5.00", keyboardType = KeyboardType.Decimal)
                FieldRow(stringResource(R.string.inference_hard_limit), budget, { budget = it }, placeholder = stringResource(R.string.inference_none), keyboardType = KeyboardType.Decimal, error = ProfileProblem.Budget in problems)
            }
            ItemGroup(title = stringResource(R.string.inference_test), footer = stringResource(R.string.inference_test_footer)) {
                val capable = testMachines.filter { it.online && it.capable }
                if (capable.isEmpty()) {
                    Item(title = stringResource(R.string.inference_no_capable_machine), enabled = false, showChevron = false)
                }
                for (machine in capable) {
                    Choice(machine.name, machine.id == testMachineId) { testMachineId = machine.id }
                }
                Item(
                    title = stringResource(R.string.inference_test_connection),
                    icon = { ItemIcon(Ionicon.FlashOutline, colors.textLink) },
                    enabled = problems.isEmpty() && capable.any { it.id == testMachineId } && !testing,
                    loading = testing,
                    onClick = { capable.find { it.id == testMachineId }?.let { onTest(it, draft) } },
                    showChevron = false,
                    modifier = Modifier.testTag(ProviderTags.TEST),
                )
                testResult?.let { ConnectionResult(it) }
            }
            ItemGroup {
                Item(
                    title = stringResource(R.string.common_save),
                    icon = { ItemIcon(Ionicon.CheckmarkOutline, colors.textLink) },
                    enabled = problems.isEmpty(),
                    onClick = { onSave(draft) },
                    showChevron = false,
                    modifier = Modifier.testTag(ProviderTags.SAVE),
                )
                if (initial != null && onDelete != null) {
                    Item(
                        title = stringResource(R.string.common_delete),
                        icon = { ItemIcon(Ionicon.TrashOutline, colors.textDestructive) },
                        destructive = true,
                        onClick = { onDelete(initial) },
                        showChevron = false,
                        modifier = Modifier.testTag(ProviderTags.DELETE),
                    )
                }
            }
        }
    }
}

@Composable
private fun Choice(title: String, selected: Boolean, onClick: () -> Unit) {
    val colors = HappyTheme.colors
    val on = stringResource(R.string.inference_selected)
    Item(
        title = title,
        onClick = onClick,
        showChevron = false,
        rightElement = if (selected) ({ Icon(Ionicon.Checkmark, 20.dp, colors.header.tint) }) else null,
        modifier = Modifier.semantics { if (selected) stateDescription = on },
    )
}

internal fun serverToolName(tool: ServerTool): Int = when (tool) {
    ServerTool.WebSearch -> R.string.inference_tool_web_search
    ServerTool.WebFetch -> R.string.inference_tool_web_fetch
    ServerTool.Datetime -> R.string.inference_tool_datetime
}

@Composable
internal fun modelFacts(model: OpenRouterModel): String = buildList {
    add(model.id)
    model.contextLength?.let { add(stringResource(R.string.inference_context, formatTokens(it))) }
    if (model.inputModalities.isNotEmpty()) add(stringResource(R.string.inference_inputs, model.inputModalities.joinToString(", ")))
    add(stringResource(if (model.supportsTools) R.string.inference_tools_yes else R.string.inference_tools_no))
    if (model.supportsReasoning) add(stringResource(R.string.inference_reasoning))
    val prompt = model.promptPerMillion
    val completion = model.completionPerMillion
    if (prompt != null && completion != null) add(stringResource(R.string.inference_price, formatUsd(prompt), formatUsd(completion)))
}.joinToString(" · ")

/**
 * OpenRouter's model list: search by id or name, favourites first, and
 * each model's context, inputs, tool calling, reasoning and published
 * price. Claude Code needs tool calling, so "Tool calling only" is on by
 * default.
 */
@Composable
fun ModelBrowserScreen(
    state: CatalogState,
    favorites: List<String>,
    onToggleFavorite: (String) -> Unit,
    onPick: (OpenRouterModel) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = HappyTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var toolsOnly by rememberSaveable { mutableStateOf(true) }
    Screen(header = { StackHeader(stringResource(R.string.inference_models_title), onBack) }, avoidKeyboard = true) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Ionicon.SearchOutline, 20.dp, colors.textSecondary)
                val searchLabel = stringResource(R.string.inference_search_models)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(searchLabel, style = ItemSpec.title, color = colors.input.placeholder)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = ItemSpec.title.copy(color = colors.text),
                        cursorBrush = SolidColor(colors.textLink),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = searchLabel },
                    )
                }
            }
            val toolsLabel = stringResource(R.string.inference_tools_only)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Switch) { toolsOnly = !toolsOnly }.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(toolsLabel, style = ItemSpec.subtitle, color = colors.textSecondary, modifier = Modifier.weight(1f))
                HappySwitch(checked = toolsOnly, onCheckedChange = { toolsOnly = it }, label = toolsLabel)
            }
            when (state) {
                CatalogState.Idle, CatalogState.Loading -> Message(stringResource(R.string.inference_loading_models))
                is CatalogState.Failed -> {
                    Message(stringResource(if (state.offline) R.string.inference_models_offline else R.string.inference_models_failed))
                    ItemList(Modifier.heightIn(max = 120.dp)) {
                        ItemGroup { Item(title = stringResource(R.string.common_retry), onClick = onRetry, showChevron = false) }
                    }
                }
                is CatalogState.Loaded -> {
                    val results = OpenRouterCatalog.search(state.models, query, favorites, toolsOnly)
                    if (state.stale) Message(stringResource(R.string.inference_models_stale))
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(results, key = { it.id }) { model ->
                            val favorite = model.id in favorites
                            val favoriteLabel = stringResource(if (favorite) R.string.inference_unfavorite else R.string.inference_favorite)
                            Item(
                                title = model.name,
                                subtitle = modelFacts(model),
                                subtitleLines = 3,
                                onClick = { onPick(model) },
                                showChevron = false,
                                rightElement = {
                                    Box(
                                        Modifier
                                            .clickable(role = Role.Button) { onToggleFavorite(model.id) }
                                            .semantics { contentDescription = favoriteLabel }
                                            .padding(12.dp),
                                    ) {
                                        Icon(if (favorite) Ionicon.Heart else Ionicon.HeartOutline, 22.dp, if (favorite) colors.textDestructive else colors.textSecondary)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = ItemSpec.subtitle, color = HappyTheme.colors.textSecondary, modifier = Modifier.fillMaxWidth().padding(16.dp))
}
