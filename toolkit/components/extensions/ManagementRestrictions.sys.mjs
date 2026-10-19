/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { AddonManager } from "resource://gre/modules/AddonManager.sys.mjs";
import { ExtensionParent } from "resource://gre/modules/ExtensionParent.sys.mjs";

const Services = globalThis.Services;
const ADMIN_MODE_PREF = "extensions.managementRestrictions.adminMode";
const CONTROLLER_ID_PREF = "extensions.managementRestrictions.controllerId";
const REQUEST_TOPIC = "webextension-admin-mode-request";
const RESPONSE_TOPIC = "webextension-admin-mode-response";
export const ADMIN_MODE_CHANGED_TOPIC = "webextension-admin-mode-changed";
const REQUEST_TIMEOUT_MS = 5000;

let requestCounter = 0;
let addonListenerRegistered = false;
const pendingRequests = new Map();

function getControllerId() {
  return Services.prefs.getStringPref(CONTROLLER_ID_PREF, "");
}

async function findActiveControllerId() {
  const addons = await AddonManager.getAllAddons();
  return (
    addons.find(addon => {
      const extension = ExtensionParent.GlobalManager.getExtension(addon.id);
      return (
        addon.isActive &&
        extension?.permissions.has("managementRestrictions")
      );
    })?.id ?? ""
  );
}

function setAdminModeEnabled(enabled, controllerId = "") {
  Services.prefs.setBoolPref(ADMIN_MODE_PREF, enabled);
  if (controllerId) {
    Services.prefs.setStringPref(CONTROLLER_ID_PREF, controllerId);
  } else if (enabled) {
    Services.prefs.clearUserPref(CONTROLLER_ID_PREF);
  }
  Services.obs.notifyObservers(null, ADMIN_MODE_CHANGED_TOPIC, String(enabled));
}

function registerAddonListener() {
  if (addonListenerRegistered) {
    return;
  }
  addonListenerRegistered = true;
  AddonManager.addAddonListener({
    onDisabled(addon) {
      if (addon.id === getControllerId()) {
        setAdminModeEnabled(true);
      }
      finishPendingRequestsForController(addon.id);
    },
    onUninstalled(addon) {
      if (addon.id === getControllerId()) {
        setAdminModeEnabled(true);
      }
      finishPendingRequestsForController(addon.id);
    },
  });
}

function finishPendingRequestsForController(controllerId) {
  for (const request of pendingRequests.values()) {
    if (request.controllerId === controllerId) {
      setAdminModeEnabled(true);
      request.finish(true);
    }
  }
}

export function isAdminModeEnabled() {
  return Services.prefs.getBoolPref(ADMIN_MODE_PREF, true);
}

export async function ensureAdminModeEnabled() {
  registerAddonListener();
  if (!isAdminModeEnabled()) {
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

export async function setAdminModeEnabledByUser(enabled) {
  registerAddonListener();
  if (enabled) {
    return requestAdminMode();
  }

  const controllerId =
    getControllerId() || (await findActiveControllerId());
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
  const controllerId = await findActiveControllerId();
  if (!controllerId) {
    setAdminModeEnabled(true);
    return true;
  }
  const requestId = `${Services.uuid.generateUUID().toString()}-${
    ++requestCounter
  }`;

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
      setAdminModeEnabled(true);
      finish(true);
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
