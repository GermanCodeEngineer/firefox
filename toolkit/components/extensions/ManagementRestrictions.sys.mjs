/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { AddonManager as FirefoxAddonManager } from "resource://gre/modules/AddonManager.sys.mjs";
import { ExtensionParent } from "resource://gre/modules/ExtensionParent.sys.mjs";
import { setTimeout, clearTimeout } from "resource://gre/modules/Timer.sys.mjs";
import { OSKeyStore } from "resource://gre/modules/OSKeyStore.sys.mjs";

const ADMIN_MODE_PREF = "extensions.managementRestrictions.adminMode";
const CONTROLLER_ID_PREF = "extensions.managementRestrictions.controllerId";
const REQUEST_TOPIC = "webextension-admin-mode-request";
const RESPONSE_TOPIC = "webextension-admin-mode-response";
export const ADMIN_MODE_CHANGED_TOPIC = "webextension-admin-mode-changed";
const REQUEST_TIMEOUT_MS = 5000;
const TOTP_SECRET_PREF = "extensions.managementRestrictions.totpSecret";
const TOTP_RECOVERY_HASH_PREF =
  "extensions.managementRestrictions.totpRecoveryHash";
const TOTP_LAST_STEP_PREF = "extensions.managementRestrictions.totpLastStep";
const TOTP_FAILURES_PREF = "extensions.managementRestrictions.totpFailures";
const TOTP_LOCKOUT_PREF = "extensions.managementRestrictions.totpLockoutUntil";
const TOTP_STEP_SECONDS = 30;
const TOTP_MAX_FAILURES = 5;
const TOTP_LOCKOUT_MS = 5 * 60 * 1000;

let requestCounter = 0;
let addonListenerRegistered = false;
const pendingRequests = new Map();

function getControllerId() {
  return Services.prefs.getStringPref(CONTROLLER_ID_PREF, "");
}

function getPrefString(name) {
  return Services.prefs.getStringPref(name, "");
}

function base32Encode(bytes) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0;
  let value = 0;
  let result = "";
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      result += alphabet[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits) {
    result += alphabet[(value << (5 - bits)) & 31];
  }
  return result;
}

function base32Decode(value) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0;
  let buffer = 0;
  const result = [];
  for (const char of value.toUpperCase().replace(/=|\s/g, "")) {
    const digit = alphabet.indexOf(char);
    if (digit < 0) {
      throw new Error("Invalid Base32 secret");
    }
    buffer = (buffer << 5) | digit;
    bits += 5;
    if (bits >= 8) {
      result.push((buffer >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return new Uint8Array(result);
}

async function getTotp(secret, step) {
  const key = await crypto.subtle.importKey(
    "raw",
    base32Decode(secret),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"]
  );
  const counter = new Uint8Array(8);
  new DataView(counter.buffer).setBigUint64(0, BigInt(step));
  const digest = new Uint8Array(await crypto.subtle.sign("HMAC", key, counter));
  const offset = digest[digest.length - 1] & 15;
  const binary =
    ((digest[offset] & 127) << 24) |
    (digest[offset + 1] << 16) |
    (digest[offset + 2] << 8) |
    digest[offset + 3];
  return String(binary % 1000000).padStart(6, "0");
}

async function matchingTotpStep(secret, code) {
  const currentStep = Math.floor(Date.now() / 1000 / TOTP_STEP_SECONDS);
  for (const step of [currentStep - 1, currentStep, currentStep + 1]) {
    if ((await getTotp(secret, step)) === code) {
      return step;
    }
  }
  return -1;
}

async function sha256(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), byte =>
    byte.toString(16).padStart(2, "0")
  ).join("");
}

function constantTimeEqual(left, right) {
  if (left.length !== right.length) {
    return false;
  }
  let difference = 0;
  for (let index = 0; index < left.length; index++) {
    difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return difference === 0;
}

function isTotpConfigured() {
  return !!getPrefString(TOTP_SECRET_PREF);
}

function clearUserPrefIfSet(name) {
  if (Services.prefs.prefHasUserValue(name)) {
    Services.prefs.clearUserPref(name);
  }
}

async function clearTotpConfiguration() {
  for (const pref of [
    TOTP_SECRET_PREF,
    TOTP_RECOVERY_HASH_PREF,
    TOTP_LAST_STEP_PREF,
    TOTP_FAILURES_PREF,
    TOTP_LOCKOUT_PREF,
  ]) {
    clearUserPrefIfSet(pref);
  }
}

async function acceptTotpCode(code) {
  const nowSeconds = Math.floor(Date.now() / 1000);
  const lockoutUntil = Services.prefs.getIntPref(TOTP_LOCKOUT_PREF, 0);
  if (lockoutUntil > nowSeconds) {
    return false;
  }
  let secret;
  try {
    secret = await OSKeyStore.decrypt(
      getPrefString(TOTP_SECRET_PREF),
      "adminMode"
    );
    const step = await matchingTotpStep(secret, code);
    const previousStep = Services.prefs.getIntPref(TOTP_LAST_STEP_PREF, -1);
    if (step >= 0 && step > previousStep) {
      Services.prefs.setIntPref(TOTP_LAST_STEP_PREF, step);
      clearUserPrefIfSet(TOTP_FAILURES_PREF);
      clearUserPrefIfSet(TOTP_LOCKOUT_PREF);
      return true;
    }
  } catch (error) {
    console.error("Admin Mode authenticator verification failed", error);
  } finally {
    secret = null;
  }
  const failures = Services.prefs.getIntPref(TOTP_FAILURES_PREF, 0) + 1;
  Services.prefs.setIntPref(TOTP_FAILURES_PREF, failures);
  if (failures >= TOTP_MAX_FAILURES) {
    Services.prefs.setIntPref(
      TOTP_LOCKOUT_PREF,
      nowSeconds + TOTP_LOCKOUT_MS / 1000
    );
  }
  return false;
}

async function authenticateTotp(code) {
  const normalizedCode = code.trim().replace(/\s/g, "");
  if (
    /^\d{6}$/.test(normalizedCode) &&
    (await acceptTotpCode(normalizedCode))
  ) {
    return { authorized: true, recovered: false };
  }
  const recoveryHash = getPrefString(TOTP_RECOVERY_HASH_PREF);
  if (recoveryHash && normalizedCode) {
    const suppliedHash = await sha256(normalizedCode.toUpperCase());
    if (constantTimeEqual(suppliedHash, recoveryHash)) {
      await clearTotpConfiguration();
      return { authorized: true, recovered: true };
    }
  }
  return { authorized: false, recovered: false };
}

function makeRecoveryCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
}

async function findActiveControllerId() {
  return (
    Array.from(ExtensionParent.GlobalManager.extensionMap.values()).find(
      extension => extension.permissions.has("managementRestrictions")
    )?.id ?? ""
  );
}

function setAdminModeEnabled(enabled, controllerId = "") {
  Services.prefs.setBoolPref(ADMIN_MODE_PREF, enabled);
  if (controllerId) {
    Services.prefs.setStringPref(CONTROLLER_ID_PREF, controllerId);
  } else if (enabled || isTotpConfigured()) {
    Services.prefs.clearUserPref(CONTROLLER_ID_PREF);
  }
  Services.obs.notifyObservers(null, ADMIN_MODE_CHANGED_TOPIC, String(enabled));
}

function registerAddonListener() {
  if (addonListenerRegistered) {
    return;
  }
  addonListenerRegistered = true;
  FirefoxAddonManager.addAddonListener({
    onDisabling(addon) {
      if (addon.id === getControllerId() && !isTotpConfigured()) {
        setAdminModeEnabled(true);
      }
      finishPendingRequestsForController(addon.id);
    },
    onDisabled(addon) {
      if (addon.id === getControllerId() && !isTotpConfigured()) {
        setAdminModeEnabled(true);
      }
      finishPendingRequestsForController(addon.id);
    },
    onUninstalling(addon) {
      if (addon.id === getControllerId() && !isTotpConfigured()) {
        setAdminModeEnabled(true);
      }
      finishPendingRequestsForController(addon.id);
    },
    onUninstalled(addon) {
      if (addon.id === getControllerId() && !isTotpConfigured()) {
        setAdminModeEnabled(true);
      }
      finishPendingRequestsForController(addon.id);
    },
  });
}

function finishPendingRequestsForController(controllerId) {
  for (const request of pendingRequests.values()) {
    if (request.controllerId === controllerId) {
      const allowed = !isTotpConfigured();
      if (allowed) {
        setAdminModeEnabled(true);
      }
      request.finish(allowed);
    }
  }
}

export function isAdminModeEnabled() {
  return Services.prefs.getBoolPref(ADMIN_MODE_PREF, true);
}

export async function ensureAdminModeEnabled() {
  registerAddonListener();
  if (!isAdminModeEnabled()) {
    if (isTotpConfigured()) {
      return false;
    }
    const controllerId = getControllerId();
    const extension = controllerId
      ? ExtensionParent.GlobalManager.getExtension(controllerId)
      : null;
    if (!extension?.permissions.has("managementRestrictions")) {
      setAdminModeEnabled(true);
    }
  }
  return isAdminModeEnabled();
}

export async function setAdminModeEnabledByUser(enabled, code = "") {
  registerAddonListener();
  if (enabled) {
    if (isTotpConfigured()) {
      const result = await authenticateTotp(code);
      if (!result.authorized) {
        return false;
      }
      setAdminModeEnabled(true);
      return true;
    }
    return requestAdminMode();
  }

  if (isTotpConfigured()) {
    setAdminModeEnabled(false);
    return false;
  }

  const controllerId = getControllerId() || (await findActiveControllerId());
  const extension = controllerId
    ? ExtensionParent.GlobalManager.getExtension(controllerId)
    : null;
  if (!extension?.permissions.has("managementRestrictions")) {
    setAdminModeEnabled(true);
    return true;
  }
  setAdminModeEnabled(false, controllerId);
  return false;
}

export async function requestAdminMode() {
  registerAddonListener();
  if (isTotpConfigured()) {
    return false;
  }
  const controllerId = await findActiveControllerId();
  if (!controllerId) {
    setAdminModeEnabled(true);
    return true;
  }
  const requestId = `${Services.uuid.generateUUID().toString()}-${++requestCounter}`;

  return new Promise(resolve => {
    let settled = false;
    let timeout;
    const finish = enabled => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timeout);
      pendingRequests.delete(requestId);
      Services.obs.removeObserver(responseObserver, RESPONSE_TOPIC);
      resolve(enabled);
    };
    const responseObserver = async subject => {
      const response = subject.wrappedJSObject;
      if (response.requestId !== requestId) {
        return;
      }
      const extension = ExtensionParent.GlobalManager.getExtension(
        response.extensionId
      );
      if (
        response.extensionId !== controllerId ||
        !extension?.permissions.has("managementRestrictions")
      ) {
        return;
      }
      setAdminModeEnabled(!!response.allow, response.extensionId);
      finish(!!response.allow);
    };
    timeout = setTimeout(() => {
      const allowed = !isTotpConfigured();
      if (allowed) {
        setAdminModeEnabled(true);
      }
      finish(allowed);
    }, REQUEST_TIMEOUT_MS);
    pendingRequests.set(requestId, { controllerId, finish });

    Services.obs.addObserver(responseObserver, RESPONSE_TOPIC);
    Services.obs.notifyObservers(
      {
        wrappedJSObject: {
          extensionId: controllerId,
          requestId,
        },
      },
      REQUEST_TOPIC
    );
  });
}

export const ManagementRestrictions = {
  beginTotpSetup() {
    const secretBytes = crypto.getRandomValues(new Uint8Array(20));
    const secret = base32Encode(secretBytes);
    const issuer = Services.appinfo.name;
    const label = `${issuer}: Admin Mode`;
    const otpauthURI =
      `otpauth://totp/${encodeURIComponent(label)}` +
      `?secret=${secret}&issuer=${encodeURIComponent(issuer)}` +
      "&algorithm=SHA1&digits=6&period=30";
    return { secret, recoveryCode: makeRecoveryCode(), otpauthURI };
  },
  async completeTotpSetup(secret, recoveryCode, code) {
    const normalizedSecret = secret.replace(/\s|=/g, "").toUpperCase();
    const normalizedCode = code.trim();
    if (!/^\d{6}$/.test(normalizedCode)) {
      return false;
    }
    const acceptedStep = await matchingTotpStep(
      normalizedSecret,
      normalizedCode
    );
    if (acceptedStep < 0) {
      return false;
    }
    const encryptedSecret = await OSKeyStore.encrypt(normalizedSecret);
    Services.prefs.setStringPref(TOTP_SECRET_PREF, encryptedSecret);
    Services.prefs.setStringPref(
      TOTP_RECOVERY_HASH_PREF,
      await sha256(recoveryCode.toUpperCase())
    );
    Services.prefs.setIntPref(TOTP_LAST_STEP_PREF, acceptedStep);
    clearUserPrefIfSet(TOTP_FAILURES_PREF);
    clearUserPrefIfSet(TOTP_LOCKOUT_PREF);
    setAdminModeEnabled(false);
    return true;
  },
  isTotpConfigured,
  ensureAdminModeEnabled,
  isAdminModeEnabled,
  setAdminModeEnabledByUser,
};
