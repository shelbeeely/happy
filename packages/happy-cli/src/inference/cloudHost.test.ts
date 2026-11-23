/**
 * Cloud host support: headless sign-in decision, the SDK's bundled Claude
 * counting as available, and the host-kind label.
 */
import { describe, expect, it } from 'vitest';
import { join } from 'node:path';
import { shouldUseHeadlessAuth } from '@/ui/auth';
import { bundledClaudeBinary } from '@/utils/detectCLI';
import { hostKindFromEnv } from '@/daemon/run';

describe('headless sign-in', () => {
    it('is used without a terminal and can be forced either way', () => {
        expect(shouldUseHeadlessAuth({}, undefined)).toBe(true);
        expect(shouldUseHeadlessAuth({}, false)).toBe(true);
        expect(shouldUseHeadlessAuth({}, true)).toBe(false);
        expect(shouldUseHeadlessAuth({ HAPPY_AUTH_HEADLESS: '1' }, true)).toBe(true);
        expect(shouldUseHeadlessAuth({ HAPPY_AUTH_HEADLESS: '0' }, false)).toBe(false);
    });
});

describe('bundled Claude Code', () => {
    const root = '/x/node_modules';
    const resolver = (installed: string[]) => (id: string) => {
        const pkg = id.replace(/\/package\.json$/, '');
        if (!installed.includes(pkg)) throw new Error('not found');
        return join(root, pkg, 'package.json');
    };

    it('finds the platform binary, musl as a fallback on Linux', () => {
        const all = () => true;
        expect(bundledClaudeBinary('linux', 'x64', resolver(['@anthropic-ai/claude-agent-sdk-linux-x64']), all))
            .toBe(join(root, '@anthropic-ai/claude-agent-sdk-linux-x64', 'claude'));
        expect(bundledClaudeBinary('linux', 'arm64', resolver(['@anthropic-ai/claude-agent-sdk-linux-arm64-musl']), all))
            .toBe(join(root, '@anthropic-ai/claude-agent-sdk-linux-arm64-musl', 'claude'));
        expect(bundledClaudeBinary('win32', 'x64', resolver(['@anthropic-ai/claude-agent-sdk-win32-x64']), all))
            .toBe(join(root, '@anthropic-ai/claude-agent-sdk-win32-x64', 'claude.exe'));
    });

    it('is absent when the package or its binary is missing', () => {
        expect(bundledClaudeBinary('linux', 'x64', resolver([]), () => true)).toBeUndefined();
        expect(bundledClaudeBinary('linux', 'x64', resolver(['@anthropic-ai/claude-agent-sdk-linux-x64']), () => false)).toBeUndefined();
    });

    it('resolves in this checkout', () => {
        expect(bundledClaudeBinary()).toBeDefined();
    });
});

describe('host kind', () => {
    it('labels a cloud host and ignores anything malformed', () => {
        expect(hostKindFromEnv({ HAPPY_HOST_KIND: 'Cloud' })).toEqual({ hostKind: 'cloud' });
        expect(hostKindFromEnv({})).toEqual({});
        expect(hostKindFromEnv({ HAPPY_HOST_KIND: 'x y; rm -rf' })).toEqual({});
    });
});
