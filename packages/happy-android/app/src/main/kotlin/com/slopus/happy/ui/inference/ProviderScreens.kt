package com.slopus.happy.ui.inference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.slopus.happy.R
import com.slopus.happy.inference.ConnectionProblem
import com.slopus.happy.inference.ConnectionTest
import com.slopus.happy.inference.CredentialRefs
import com.slopus.happy.inference.HostCredentials
import com.slopus.happy.inference.InferenceProfile
import com.slopus.happy.ui.components.Icon
import com.slopus.happy.ui.components.Ionicon
import com.slopus.happy.ui.components.Item
import com.slopus.happy.ui.components.ItemGroup
import com.slopus.happy.ui.components.ItemList
import com.slopus.happy.ui.components.ItemSpec
import com.slopus.happy.ui.components.Screen
import com.slopus.happy.ui.components.StackHeader
import com.slopus.happy.ui.settings.ItemIcon
import com.slopus.happy.ui.theme.HappyTheme

object ProviderTags {
    const val ADD = "providers.add"
    const val PROFILE = "providers.profile"
    const val MACHINE = "providers.machine"
    const val STORE_KEY = "providers.storeKey"
    const val FIELD = "providers.field"
    const val TEST = "providers.test"
    const val SAVE = "providers.save"
    const val DELETE = "providers.delete"
    const val TEST_RESULT = "providers.testResult"
}

/** One Happy CLI computer as the providers page shows it. */
@Immutable
data class ProviderMachine(
    val id: String,
    val name: String,
    val online: Boolean,
    /** The computer's Happy CLI accepts OpenRouter profiles. */
    val capable: Boolean,
    /** What the computer said about its keys; null while asking or when it couldn't be asked. */
    val credentials: HostCredentials?,
    val checking: Boolean = false,
)

/**
 * Settings › AI providers. Profiles (OpenRouter model, routing, server
 * tools, budget) sync with the account; keys stay on each computer, which
 * this page can check and, if asked, store a key on (end-to-end encrypted).
 * Saving a profile configures nothing by itself: a computer applies it when
 * it starts a session, and the session says so.
 */
@Composable
fun ProvidersScreen(
    profiles: List<InferenceProfile>,
    machines: List<ProviderMachine>,
    onAdd: () -> Unit,
    onEdit: (InferenceProfile) -> Unit,
    onStoreKey: (ProviderMachine) -> Unit,
    onCheck: (ProviderMachine) -> Unit,
    onLocalAi: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = HappyTheme.colors
    Screen(header = { StackHeader(stringResource(R.string.inference_providers_title), onBack) }) {
        ItemList {
            ItemGroup(title = stringResource(R.string.inference_profiles), footer = stringResource(R.string.inference_profiles_footer)) {
                for (profile in profiles.sortedBy { it.name.lowercase() }) {
                    Item(
                        title = profile.name,
                        subtitle = listOfNotNull(profile.effectiveModel, credentialLabel(profile.credentialRef)).joinToString(" · "),
                        icon = { ItemIcon(Ionicon.SparklesOutline, colors.textLink) },
                        onClick = { onEdit(profile) },
                        modifier = Modifier.testTag(ProviderTags.PROFILE),
                    )
                }
                Item(
                    title = stringResource(R.string.inference_add_profile),
                    icon = { ItemIcon(Ionicon.AddOutline, colors.textLink) },
                    onClick = onAdd,
                    showChevron = false,
                    modifier = Modifier.testTag(ProviderTags.ADD),
                )
            }

            ItemGroup(title = stringResource(R.string.settings_machines), footer = stringResource(R.string.inference_machines_footer)) {
                if (machines.isEmpty()) {
                    Item(title = stringResource(R.string.settings_agents_no_machines), enabled = false, showChevron = false)
                }
                for (machine in machines) {
                    Item(
                        title = machine.name,
                        subtitle = machineSummary(machine),
                        subtitleLines = 4,
                        icon = { ItemIcon(Ionicon.DesktopOutline, if (machine.online) colors.status.connected else colors.status.disconnected) },
                        loading = machine.checking,
                        onClick = if (machine.online && machine.capable) ({ onCheck(machine) }) else null,
                        showChevron = false,
                        modifier = Modifier.testTag(ProviderTags.MACHINE).alpha(if (machine.online) 1f else 0.6f),
                    )
                    if (machine.online && machine.capable) {
                        Item(
                            title = stringResource(R.string.inference_store_key),
                            subtitle = stringResource(R.string.inference_store_key_subtitle),
                            icon = { ItemIcon(Ionicon.KeyOutline, colors.textLink) },
                            onClick = { onStoreKey(machine) },
                            showChevron = false,
                            modifier = Modifier.testTag(ProviderTags.STORE_KEY),
                        )
                    }
                }
            }

            ItemGroup {
                Item(
                    title = stringResource(R.string.localai_settings_title),
                    subtitle = stringResource(R.string.localai_settings_subtitle),
                    icon = { ItemIcon(Ionicon.PhonePortraitOutline, colors.textLink) },
                    onClick = onLocalAi,
                )
            }
        }
    }
}

@Composable
private fun machineSummary(machine: ProviderMachine): String {
    if (!machine.online) return stringResource(R.string.status_offline)
    if (!machine.capable) return stringResource(R.string.inference_cli_update_needed)
    val credentials = machine.credentials ?: return stringResource(if (machine.checking) R.string.inference_checking else R.string.inference_tap_to_check)
    val stored = credentials.present[CredentialRefs.HAPPY_STORED] == true
    val env = credentials.present[CredentialRefs.ENV_DEFAULT] == true
    return buildList {
        add(stringResource(if (stored) R.string.inference_key_stored_yes else R.string.inference_key_stored_no))
        add(stringResource(if (env) R.string.inference_key_env_yes else R.string.inference_key_env_no))
        if (credentials.oriInstalled) {
            add(stringResource(R.string.inference_ori_detected, credentials.oriVersion ?: "?") + (credentials.oriDefaultModel?.let { " · $it" } ?: ""))
        }
    }.joinToString("\n")
}

@Composable
internal fun credentialLabel(ref: String): String = when {
    ref == CredentialRefs.HAPPY_STORED -> stringResource(R.string.inference_credential_stored)
    else -> CredentialRefs.envName(ref)?.let { "\$$it" } ?: ref
}

/** A labelled single-line field drawn like an item row. */
@Composable
internal fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    tag: String = ProviderTags.FIELD,
    error: Boolean = false,
) {
    val colors = HappyTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ItemSpec.minHeight)
            .padding(horizontal = ItemSpec.paddingHorizontal, vertical = 10.dp),
    ) {
        Text(label, style = ItemSpec.subtitle, color = if (error) colors.textDestructive else colors.textSecondary)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            if (value.isEmpty()) Text(placeholder, style = ItemSpec.title, color = colors.input.placeholder)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = ItemSpec.title.copy(color = colors.text),
                cursorBrush = SolidColor(colors.textLink),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = keyboardType,
                ),
                modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            )
        }
    }
}

/** The result of a connection test, worded per problem. */
@Composable
internal fun ConnectionResult(result: ConnectionTest) {
    val colors = HappyTheme.colors
    val (icon, tint, title, detail) = when (result) {
        is ConnectionTest.Passed -> Quad(
            Ionicon.CheckmarkCircleOutline,
            colors.success,
            stringResource(R.string.inference_test_passed),
            listOfNotNull(
                result.keyLabel?.let { stringResource(R.string.inference_test_key, it) },
                result.limitUsd?.let { limit -> stringResource(R.string.inference_test_limit, formatUsd(result.usageUsd ?: 0.0), formatUsd(limit)) },
                result.contextLength?.let { stringResource(R.string.inference_context, formatTokens(it)) },
                result.serverTools.takeIf { it.isNotEmpty() }?.let { tools -> stringResource(R.string.inference_test_tools, tools.joinToString { it.wire }) },
            ).joinToString("\n"),
        )
        is ConnectionTest.Failed -> Quad(
            Ionicon.WarningOutline,
            colors.textDestructive,
            stringResource(problemText(result.problem)),
            result.detail.orEmpty(),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().testTag(ProviderTags.TEST_RESULT).semantics(mergeDescendants = true) {}
            .padding(horizontal = ItemSpec.paddingHorizontal, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(ItemSpec.iconGap),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, 24.dp, tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = ItemSpec.title, color = colors.text)
            if (detail.isNotBlank()) Text(detail, style = ItemSpec.subtitle, color = colors.textSecondary)
        }
    }
}

internal fun problemText(problem: ConnectionProblem): Int = when (problem) {
    ConnectionProblem.MissingCredential -> R.string.inference_problem_missing_credential
    ConnectionProblem.AuthFailed -> R.string.inference_problem_auth
    ConnectionProblem.ModelNotFound -> R.string.inference_problem_model_not_found
    ConnectionProblem.ModelNoTools -> R.string.inference_problem_model_no_tools
    ConnectionProblem.ServerToolRejected -> R.string.inference_problem_server_tool
    ConnectionProblem.ProviderUnreachable -> R.string.inference_problem_provider_unreachable
    ConnectionProblem.InvalidRequest -> R.string.inference_problem_invalid
    ConnectionProblem.HostUnreachable -> R.string.inference_problem_host_unreachable
    ConnectionProblem.HostOutdated -> R.string.inference_cli_update_needed
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

internal fun formatUsd(value: Double): String = when {
    value == 0.0 -> "$0"
    value < 0.01 -> "<$0.01"
    value < 100 -> "$" + String.format(java.util.Locale.US, "%.2f", value)
    else -> "$" + String.format(java.util.Locale.US, "%,.0f", value)
}

internal fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0).replace(".0M", "M")
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}
