import { execSync } from 'child_process';
import os from 'os';
import { existsSync } from 'fs';
import { join } from 'path';
import { findAgyBin } from '@/agy/constants';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

/**
 * The Agent SDK's own Claude Code binary for this platform. Daemon-started
 * sessions always run remote mode through the SDK, which uses this binary,
 * so a host with it can run Claude sessions without `claude` on PATH (a
 * cloud host or container). Local mode still needs a real `claude`.
 */
export function bundledClaudeBinary(
    platform: NodeJS.Platform = process.platform,
    arch: string = process.arch,
    resolve: (id: string) => string = require.resolve,
    exists: (path: string) => boolean = existsSync,
): string | undefined {
  const name = platform === 'win32' ? 'claude.exe' : 'claude';
  const base = `@anthropic-ai/claude-agent-sdk-${platform}-${arch}`;
  for (const pkg of platform === 'linux' ? [base, `${base}-musl`] : [base]) {
    try {
      const candidate = join(resolve(`${pkg}/package.json`), '..', name);
      if (exists(candidate)) return candidate;
    } catch {
      // Not installed for this platform; try the next.
    }
  }
  return undefined;
}

export interface CLIAvailability {
  claude: boolean;
  codex: boolean;
  gemini: boolean;
  openclaw: boolean;
  agy: boolean;
  detectedAt: number;
}

/**
 * Detects which CLI tools are available on this machine.
 * Cross-platform: uses `command -v` on POSIX, `Get-Command` on Windows.
 */
export function detectCLIAvailability(): CLIAvailability {
  const isWindows = os.platform() === 'win32';

  if (isWindows) {
    return detectWindows();
  }
  return detectPosix();
}

function commandExists(command: string): boolean {
  try {
    execSync(`command -v ${command} >/dev/null 2>&1`, { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

function detectPosix(): CLIAvailability {
  const claude = commandExists('claude') || bundledClaudeBinary() !== undefined;
  const codex = commandExists('codex');
  const gemini = commandExists('gemini');
  const agy = findAgyBin() !== undefined;

  // OpenClaw: check command, config file, or env var
  const openclawCommand = commandExists('openclaw');
  const openclawConfig = existsSync(join(os.homedir(), '.openclaw', 'openclaw.json'));
  const openclawEnv = !!process.env.OPENCLAW_GATEWAY_URL;
  const openclaw = openclawCommand || openclawConfig || openclawEnv;

  return { claude, codex, gemini, openclaw, agy, detectedAt: Date.now() };
}

function detectWindows(): CLIAvailability {
  const checkCommand = (name: string): boolean => {
    try {
      execSync(`powershell -NoProfile -Command "Get-Command ${name} -ErrorAction SilentlyContinue"`, { stdio: 'ignore', windowsHide: true });
      return true;
    } catch {
      return false;
    }
  };

  const claude = checkCommand('claude') || bundledClaudeBinary() !== undefined;
  const codex = checkCommand('codex');
  const gemini = checkCommand('gemini');
  const agy = findAgyBin() !== undefined;

  // OpenClaw: check command, config file, or env var
  const openclawCommand = checkCommand('openclaw');
  const openclawConfig = existsSync(join(process.env.USERPROFILE || os.homedir(), '.openclaw', 'openclaw.json'));
  const openclawEnv = !!process.env.OPENCLAW_GATEWAY_URL;
  const openclaw = openclawCommand || openclawConfig || openclawEnv;

  return { claude, codex, gemini, openclaw, agy, detectedAt: Date.now() };
}
