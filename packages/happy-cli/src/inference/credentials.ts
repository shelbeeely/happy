/**
 * Host-side inference credentials.
 *
 * The phone never holds provider keys. It selects a credential *reference*
 * and the execution host resolves it here:
 * - `env:NAME` reads an environment variable visible to the daemon
 * - `happy:openrouter` reads `<happyHomeDir>/credentials/openrouter.key`
 *
 * Values returned by this module must never be logged or echoed back over
 * RPC. Errors only ever name the reference, never the value.
 */

import { promises as fs } from 'node:fs';
import { join } from 'node:path';
import { CredentialRefSchema, type CredentialRef } from '@slopus/happy-wire';
import { configuration } from '@/configuration';

/** References the host offers to the phone when listing credentials. */
export const OFFERED_CREDENTIAL_REFS: CredentialRef[] = ['happy:openrouter', 'env:OPENROUTER_API_KEY'];

const OPENROUTER_KEY_FILE = 'openrouter.key';

/** Directory holding Happy-managed provider keys. Mode 0700. */
function credentialsDir(homeDir: string): string {
    return join(homeDir, 'credentials');
}

export class CredentialError extends Error {
    constructor(readonly code: 'MISSING_CREDENTIAL' | 'INVALID_REQUEST', message: string) {
        super(message);
        this.name = 'CredentialError';
    }
}

/**
 * Resolve a credential reference to its secret value.
 * Throws CredentialError(MISSING_CREDENTIAL) when the host has no value.
 */
export async function resolveCredential(
    ref: string,
    env: NodeJS.ProcessEnv = process.env,
    homeDir: string = configuration.happyHomeDir,
): Promise<string> {
    const parsed = CredentialRefSchema.safeParse(ref);
    if (!parsed.success) {
        throw new CredentialError('INVALID_REQUEST', 'Unsupported credential reference');
    }
    if (parsed.data.startsWith('env:')) {
        const name = parsed.data.slice('env:'.length);
        const value = env[name]?.trim();
        if (!value) {
            throw new CredentialError('MISSING_CREDENTIAL', `Environment variable ${name} is not set for the Happy daemon on this host`);
        }
        return value;
    }
    try {
        const value = (await fs.readFile(join(credentialsDir(homeDir), OPENROUTER_KEY_FILE), 'utf8')).trim();
        if (!value) {
            throw new CredentialError('MISSING_CREDENTIAL', 'The stored OpenRouter key on this host is empty');
        }
        return value;
    } catch (error) {
        if (error instanceof CredentialError) throw error;
        throw new CredentialError('MISSING_CREDENTIAL', 'No OpenRouter key is stored on this host. Run `happy auth openrouter` or store one from the app.');
    }
}

/** Report which offered references currently resolve, without revealing values. */
export async function listCredentialPresence(
    env: NodeJS.ProcessEnv = process.env,
    homeDir: string = configuration.happyHomeDir,
): Promise<{ ref: CredentialRef; present: boolean }[]> {
    return Promise.all(OFFERED_CREDENTIAL_REFS.map(async (ref) => {
        try {
            await resolveCredential(ref, env, homeDir);
            return { ref, present: true };
        } catch {
            return { ref, present: false };
        }
    }));
}

/**
 * Store the Happy-managed OpenRouter key with owner-only permissions.
 * The key is validated only for shape; `testOpenRouterConnection` checks it.
 */
export async function storeOpenRouterKey(key: string, homeDir: string = configuration.happyHomeDir): Promise<void> {
    const trimmed = key.trim();
    if (trimmed.length < 8 || trimmed.length > 512 || /\s/.test(trimmed)) {
        throw new CredentialError('INVALID_REQUEST', 'That does not look like an OpenRouter API key');
    }
    const dir = credentialsDir(homeDir);
    await fs.mkdir(dir, { recursive: true, mode: 0o700 });
    await fs.chmod(dir, 0o700);
    const target = join(dir, OPENROUTER_KEY_FILE);
    const temp = `${target}.${process.pid}.tmp`;
    await fs.writeFile(temp, trimmed, { mode: 0o600 });
    await fs.rename(temp, target);
    await fs.chmod(target, 0o600);
}

/** Remove the Happy-managed OpenRouter key if present. */
export async function deleteOpenRouterKey(homeDir: string = configuration.happyHomeDir): Promise<void> {
    await fs.rm(join(credentialsDir(homeDir), OPENROUTER_KEY_FILE), { force: true });
}
