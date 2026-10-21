/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { openAbuseReport } from "./abuse-reports.mjs";
import {
  AddonManagerListenerHandler,
  PREF_UI_LASTCATEGORY,
  UPDATES_RECENT_TIMESPAN,
  checkForUpdates,
  getUpdateInstall,
  isAddonOptionsUIAllowed,
  isManualUpdate,
  isPending,
} from "./aboutaddons-utils.mjs";
import { gViewController, loadView } from "./view-controller.mjs";

const { AddonManager } = ChromeUtils.importESModule(
  "resource://gre/modules/AddonManager.sys.mjs"
);
const { XPCOMUtils } = ChromeUtils.importESModule(
  "resource://gre/modules/XPCOMUtils.sys.mjs"
);

const lazy = {};
ChromeUtils.defineESModuleGetters(lazy, {
  ManagementRestrictions:
    "resource://gre/modules/ManagementRestrictions.sys.mjs",
  recordDetailsViewTelemetry: "chrome://global/content/ml/Utils.sys.mjs",
});

const ADMIN_MODE_RESTRICTED_ACTIONS = new Set([
  "install-addon",
  "install-from-file",
  "install-update",
  "install-postponed",
  "remove",
  "toggle-disabled",
  "always-activate",
  "never-activate",
]);

let adminModeControlUpdateCount = 0;
let adminModeMutationCallbackCount = 0;

function updateAdminModeControls(root = document) {
  const enabled = lazy.ManagementRestrictions.isAdminModeEnabled();
  document.body.toggleAttribute("admin-mode-disabled", !enabled);

  const controls = [];
  if (root instanceof Element && root.hasAttribute("action")) {
    controls.push(root);
  }
  controls.push(...(root.querySelectorAll?.("[action]") ?? []));
  for (const control of controls) {
    if (!ADMIN_MODE_RESTRICTED_ACTIONS.has(control.getAttribute("action"))) {
      continue;
    }
    if (!enabled) {
      if (!control.hasAttribute("data-admin-mode-disabled")) {
        control.setAttribute(
          "data-admin-mode-disabled",
          String(control.disabled)
        );
      }
      control.disabled = true;
    } else if (control.hasAttribute("data-admin-mode-disabled")) {
      control.disabled =
        control.getAttribute("data-admin-mode-disabled") === "true";
      control.removeAttribute("data-admin-mode-disabled");
    }
  }
}

function blockRestrictedAction(event) {
  if (lazy.ManagementRestrictions.isAdminModeEnabled()) {
    return;
  }
  const control = event.composedPath().find(
    node =>
      node instanceof Element &&
      ADMIN_MODE_RESTRICTED_ACTIONS.has(node.getAttribute("action"))
  );
  if (control) {
    event.preventDefault();
    event.stopImmediatePropagation();
  }
}

XPCOMUtils.defineLazyPreferenceGetter(
  lazy,
  "LIST_RECOMMENDATIONS_ENABLED",
  "extensions.htmlaboutaddons.recommendations.enabled",
  false
);

const L10N_ID_MAPPING = {
  "theme-disabled-heading": "theme-disabled-heading2",
};

function getL10nIdMapping(id) {
  return L10N_ID_MAPPING[id] || id;
}

// Define views
gViewController.defineView("list", async type => {
  if (!AddonManager.hasAddonType(type)) {
    return null;
  }

  let frag = document.createDocumentFragment();
  let list = document.createElement("addon-list");
  list.type = type;

  let sections = [
    {
      headingId: type + "-enabled-heading",
      sectionClass: `${type}-enabled-section`,
      filterFn: addon =>
        !addon.hidden && addon.isActive && !isPending(addon, "uninstall"),
    },
  ];

  const disabledAddonsFilterFn = addon =>
    !addon.hidden && !addon.isActive && !isPending(addon, "uninstall");

  sections.push({
    headingId: getL10nIdMapping(`${type}-disabled-heading`),
    sectionClass: `${type}-disabled-section`,
    filterFn: disabledAddonsFilterFn,
  });

  // Show the forced-colors and smart window theme notices only
  // in themes list view.
  if (type === "theme") {
    const forcedColorsNotice = document.createElement("forced-colors-notice");
    frag.appendChild(forcedColorsNotice);

    const smartWindowNotice = document.createElement(
      "smartwindow-themes-notice"
    );
    frag.appendChild(smartWindowNotice);
  }

  list.setSections(sections);
  frag.appendChild(list);

  // Show recommendations for themes and extensions.
  if (
    lazy.LIST_RECOMMENDATIONS_ENABLED &&
    (type == "extension" || type == "theme")
  ) {
    let elementName =
      type == "extension"
        ? "recommended-extensions-section"
        : "recommended-themes-section";
    let recommendations = document.createElement(elementName);
    // Start loading the recommendations. This can finish after the view load
    // event is sent.
    recommendations.render();
    frag.appendChild(recommendations);
  }

  await list.render();

  return frag;
});

gViewController.defineView("detail", async param => {
  let [id, selectedTab] = param.split("/");
  let addon = await AddonManager.getAddonByID(id);

  if (!addon) {
    return null;
  }

  if (addon.type === "mlmodel") {
    lazy.recordDetailsViewTelemetry(addon);
  }

  let card = document.createElement("addon-card");

  // Ensure the category for this add-on type is selected.
  document.querySelector("categories-box").selectType(addon.type);

  // Go back to the list view when the add-on is removed.
  card.addEventListener("remove", () =>
    gViewController.loadView(`list/${addon.type}`)
  );

  card.setAddon(addon);
  card.expand();
  await card.render();
  if (selectedTab === "preferences" && (await isAddonOptionsUIAllowed(addon))) {
    card.showPrefs();
  }

  return card;
});

gViewController.defineView("updates", async param => {
  let list = document.createElement("addon-list");
  list.type = "all";
  if (param == "available") {
    list.setSections([
      {
        headingId: "available-updates-heading",
        filterFn: addon => {
          // Filter the addons visible in the updates view using the same
          // criteria that is being used to compute the counter on the
          // available updates category button badge (updateAvailableCount).
          const install = getUpdateInstall(addon);
          return install && isManualUpdate(install);
        },
      },
    ]);
    list.listenForUpdates();
  } else if (param == "recent") {
    list.sortByFn = (a, b) => {
      if (a.updateDate > b.updateDate) {
        return -1;
      }
      if (a.updateDate < b.updateDate) {
        return 1;
      }
      return 0;
    };
    let updateLimit = new Date() - UPDATES_RECENT_TIMESPAN;
    list.setSections([
      {
        headingId: "recent-updates-heading",
        filterFn: addon =>
          !addon.hidden && addon.updateDate && addon.updateDate > updateLimit,
      },
    ]);
  } else {
    throw new Error(`Unknown updates view ${param}`);
  }

  await list.render();
  return list;
});

gViewController.defineView("discover", async () => {
  let discopane = document.createElement("discovery-pane");
  discopane.render();
  await document.l10n.translateFragment(discopane);
  return discopane;
});

gViewController.defineView("shortcuts", async extensionId => {
  // Force the extension category to be selected, in the case of a reload,
  // restart, or if the view was opened from another category's page.
  document.querySelector("categories-box").selectType("extension");

  let view = document.createElement("addon-shortcuts");
  if (extensionId && extensionId !== "shortcuts") {
    view.setAttribute("extension-id", extensionId);
  }
  await view.render();
  await document.l10n.translateFragment(view);
  return view;
});

// Expose window globals expected by tests and external callers.
window.loadView = loadView;
window.gViewController = gViewController;
window.openAbuseReport = openAbuseReport;
window.getL10nIdMapping = getL10nIdMapping;
window.checkForUpdates = checkForUpdates;

/**
 * Called when about:addons is loaded.
 */
async function initialize() {
  window.addEventListener(
    "unload",
    () => {
      // Clear out the document so the disconnectedCallback will trigger
      // properly and all of the custom elements can cleanup.
      document.body.textContent = "";
      AddonManagerListenerHandler.shutdown();
    },
    { once: true }
  );

  const adminModeObserver = {
    observe() {
      updateAdminModeControls();
    },
  };
  Services.obs.addObserver(
    adminModeObserver,
    "webextension-admin-mode-changed"
  );
  const adminModeMutationObserver = new MutationObserver(records => {
    for (const record of records) {
      if (record.type === "attributes") {
        updateAdminModeControls(record.target);
      } else {
        for (const node of record.addedNodes) {
          if (node instanceof Element) {
            updateAdminModeControls(node);
          }
        }
      }
    }
  });
  adminModeMutationObserver.observe(document.body, {
    attributes: true,
    attributeFilter: ["disabled"],
    childList: true,
    subtree: true,
  });
  document.addEventListener("click", blockRestrictedAction, true);
  document.addEventListener("toggle", blockRestrictedAction, true);
  window.addEventListener(
    "unload",
    () => {
      Services.obs.removeObserver(
        adminModeObserver,
        "webextension-admin-mode-changed"
      );
      adminModeMutationObserver.disconnect();
      document.removeEventListener("click", blockRestrictedAction, true);
      document.removeEventListener("toggle", blockRestrictedAction, true);
    },
    { once: true }
  );
  await lazy.ManagementRestrictions.ensureAdminModeEnabled();
  updateAdminModeControls();

  // Init UI and view management
  gViewController.initialize(document.getElementById("main"));

  document.querySelector("categories-box").initialize();
  AddonManagerListenerHandler.startup();

  // browser.js may call loadView here if it expects an EM-loaded notification
  gViewController.notifyEMLoaded();

  // Select an initial view if no listener has set one so far
  if (!gViewController.currentViewId) {
    if (history.state) {
      // If there is a history state to restore then use that
      await gViewController.renderState(history.state);
    } else {
      // Fallback to the last category or first valid category view otherwise.
      await gViewController.loadView(
        Services.prefs.getStringPref(
          PREF_UI_LASTCATEGORY,
          gViewController.defaultViewId
        )
      );
    }
  }
}

window.promiseInitialized = new Promise(resolve => {
  window.addEventListener(
    "load",
    () => {
      initialize().then(resolve);
    },
    { once: true }
  );
});
