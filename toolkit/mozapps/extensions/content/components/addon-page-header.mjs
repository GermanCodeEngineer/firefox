/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { AboutAddonsHTMLElement } from "../aboutaddons-utils.mjs";

const { ManagementRestrictions } = ChromeUtils.importESModule(
  "resource://gre/modules/ManagementRestrictions.sys.mjs"
);
const { QR } = ChromeUtils.importESModule(
  "moz-src:///toolkit/components/qrcode/encoder.mjs"
);

class AddonPageHeader extends AboutAddonsHTMLElement {
  static get markup() {
    return `
      <template>
        <div class="sticky-container">
          <div class="main-search">
            <label
              for="search-addons"
              class="search-label"
              data-l10n-id="default-heading-search-label"
            ></label>
            <search-addons></search-addons>
          </div>
          <div class="main-heading">
            <moz-button
              type="ghost"
              iconsrc="chrome://global/skin/icons/arrow-left.svg"
              size="default"
              class="back-button"
              action="go-back"
              data-l10n-id="header-back-button"
              hidden
            ></moz-button>
            <h1 class="header-name"></h1>
            <div class="spacer"></div>
            <div class="admin-mode-control">
              <span
                data-l10n-id="addons-admin-mode-label"
              ></span>
              <moz-toggle
                class="admin-mode-toggle"
                data-l10n-id="addons-admin-mode-toggle"
              ></moz-toggle>
              <moz-button
                class="admin-mode-totp-setup"
                size="small"
                data-l10n-id="addons-admin-mode-totp-setup"
              ></moz-button>
            </div>
            <addon-updates-message
              id="updates-message"
              hidden
            ></addon-updates-message>
            <div class="page-options-menu">
              <moz-button
                size="default"
                class="more-options-button"
                iconsrc="chrome://global/skin/icons/settings.svg"
                data-l10n-id="addon-page-options-button"
              ></moz-button>
            </div>
          </div>
          <aboutaddons-themes-mode></aboutaddons-themes-mode>
        </div>
        <dialog class="admin-mode-totp-dialog">
          <section class="admin-mode-totp-enrollment">
            <h2 data-l10n-id="addons-admin-mode-totp-dialog-title"></h2>
            <p data-l10n-id="addons-admin-mode-totp-dialog-instructions"></p>
            <img class="admin-mode-totp-qr" />
            <code class="admin-mode-totp-secret"></code>
            <label>
              <span data-l10n-id="addons-admin-mode-totp-confirm-label"></span>
              <input
                class="admin-mode-totp-code"
                type="text"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
              />
            </label>
            <p class="admin-mode-totp-error" role="alert" hidden></p>
            <div class="admin-mode-totp-actions">
              <moz-button
                class="admin-mode-totp-cancel"
                data-l10n-id="addons-admin-mode-totp-cancel"
              ></moz-button>
              <moz-button
                class="admin-mode-totp-confirm"
                type="primary"
                data-l10n-id="addons-admin-mode-totp-confirm"
              ></moz-button>
            </div>
          </section>
          <section class="admin-mode-totp-recovery" hidden>
            <h2 data-l10n-id="addons-admin-mode-totp-recovery-title"></h2>
            <p data-l10n-id="addons-admin-mode-totp-recovery-instructions"></p>
            <code class="admin-mode-totp-recovery-code"></code>
            <div class="admin-mode-totp-actions">
              <moz-button
                class="admin-mode-totp-close"
                data-l10n-id="addons-admin-mode-totp-close"
              ></moz-button>
            </div>
          </section>
        </dialog>
        <global-warnings></global-warnings>
      </template>
    `;
  }

  connectedCallback() {
    if (this.childElementCount === 0) {
      this.appendChild(AddonPageHeader.fragment);
      this.heading = this.querySelector(".header-name");
      this.backButton = this.querySelector(".back-button");
      this.adminModeToggle = this.querySelector(".admin-mode-toggle");
      this.adminModeTotpSetup = this.querySelector(".admin-mode-totp-setup");
      this.totpDialog = this.querySelector(".admin-mode-totp-dialog");
      this.totpEnrollment = this.querySelector(".admin-mode-totp-enrollment");
      this.totpRecovery = this.querySelector(".admin-mode-totp-recovery");
      this.totpQR = this.querySelector(".admin-mode-totp-qr");
      this.totpSecret = this.querySelector(".admin-mode-totp-secret");
      this.totpCode = this.querySelector(".admin-mode-totp-code");
      this.totpError = this.querySelector(".admin-mode-totp-error");
      this.totpConfirmButton = this.querySelector(".admin-mode-totp-confirm");
      this.pageOptionsMenuButton = this.querySelector(".more-options-button");

      // The addon-page-options element is outside of this element since this is
      // position: sticky and that would break the positioning of the menu.
      this.pageOptionsMenu = document.getElementById(
        this.getAttribute("page-options-id")
      );

      // Wiring up the addon-page-options panel-list with the page options
      // moz-button.
      customElements
        .whenDefined("addon-page-options")
        .then(
          () =>
            (this.pageOptionsMenuButton.menuId =
              this.pageOptionsMenu.panelListId)
        );
    }
    this.adminModeToggle.addEventListener("toggle", this);
    this.totpDialog.addEventListener("close", this);
    this.totpDialog.addEventListener("cancel", this);
    this.adminModeObserver = {
      observe: () => this.updateAdminModeToggle(),
    };
    Services.obs.addObserver(
      this.adminModeObserver,
      "webextension-admin-mode-changed"
    );
    this.updateAdminModeToggle();
    document.addEventListener("view-selected", this);
    this.addEventListener("click", this);
  }

  disconnectedCallback() {
    document.removeEventListener("view-selected", this);
    this.removeEventListener("click", this);
    this.adminModeToggle.removeEventListener("toggle", this);
    this.totpDialog.removeEventListener("close", this);
    this.totpDialog.removeEventListener("cancel", this);
    Services.obs.removeObserver(
      this.adminModeObserver,
      "webextension-admin-mode-changed"
    );
  }

  updateAdminModeToggle() {
    this.adminModeToggle.pressed = ManagementRestrictions.isAdminModeEnabled();
    this.adminModeTotpSetup.hidden = ManagementRestrictions.isTotpConfigured();
  }

  setViewInfo({ type, param }) {
    this.setAttribute("current-view", type);
    this.setAttribute("current-param", param);
    let viewType = type === "list" ? param : type;
    this.setAttribute("type", viewType);

    this.heading.hidden = viewType === "detail";
    this.backButton.hidden = viewType !== "detail" && viewType !== "shortcuts";

    this.backButton.disabled = !history.state?.previousView;

    if (viewType !== "detail") {
      document.l10n.setAttributes(this.heading, `${viewType}-heading`);
    }
  }

  async handleEvent(e) {
    if (e.type === "cancel" && e.target === this.totpDialog) {
      if (this.totpSubmitting) {
        e.preventDefault();
      }
      return;
    }
    if (e.type === "close" && e.target === this.totpDialog) {
      this.pendingTotpSetup = null;
      this.totpQR.removeAttribute("src");
      if (this.totpQRURL) {
        URL.revokeObjectURL(this.totpQRURL);
      }
      this.totpQRURL = null;
      this.totpSecret.textContent = "";
      this.totpCode.value = "";
      this.totpConfirmButton.disabled = false;
      this.totpSubmitting = false;
      this.totpRecovery.querySelector(
        ".admin-mode-totp-recovery-code"
      ).textContent = "";
      return;
    }
    if (e.type === "toggle" && e.target === this.adminModeToggle) {
      this.adminModeToggle.disabled = true;
      try {
        let code = "";
        if (
          this.adminModeToggle.pressed &&
          ManagementRestrictions.isTotpConfigured()
        ) {
          code = window.prompt(
            await document.l10n.formatValue("addons-admin-mode-totp-prompt")
          );
          if (code === null) {
            return;
          }
        }
        await ManagementRestrictions.setAdminModeEnabledByUser(
          this.adminModeToggle.pressed,
          code
        );
      } finally {
        this.updateAdminModeToggle();
        this.adminModeToggle.disabled = false;
      }
      return;
    }

    if (e.type === "click") {
      if (e.target.closest(".admin-mode-totp-cancel, .admin-mode-totp-close")) {
        this.totpDialog.close();
        return;
      }
      if (e.target.closest(".admin-mode-totp-confirm")) {
        await this.confirmTotpSetup();
        return;
      }
      switch (e.target) {
        case this.adminModeTotpSetup:
          await this.setupTotp();
          break;
        case this.backButton:
          window.history.back();
          break;
      }
    } else if (e.target == document && e.type == "view-selected") {
      const { type, param } = e.detail;
      this.setViewInfo({ type, param });
    }
  }

  async setupTotp() {
    this.pendingTotpSetup = ManagementRestrictions.beginTotpSetup();
    const qrDataURI = QR.encodeToDataURI(
      this.pendingTotpSetup.otpauthURI,
      "M"
    ).src;
    const qrBytes = Uint8Array.from(
      atob(qrDataURI.slice(qrDataURI.indexOf(",") + 1)),
      character => character.charCodeAt(0)
    );
    this.totpQRURL = URL.createObjectURL(
      new Blob([qrBytes], { type: "image/gif" })
    );
    this.totpQR.src = this.totpQRURL;
    this.totpQR.alt = await document.l10n.formatValue(
      "addons-admin-mode-totp-qr-alt"
    );
    this.totpSecret.textContent = this.pendingTotpSetup.secret;
    this.totpCode.value = "";
    this.totpError.hidden = true;
    this.totpEnrollment.hidden = false;
    this.totpRecovery.hidden = true;
    this.totpDialog.showModal();
    this.totpCode.focus();
  }

  async confirmTotpSetup() {
    if (!this.pendingTotpSetup || this.totpSubmitting) {
      return;
    }
    const setup = this.pendingTotpSetup;
    this.totpSubmitting = true;
    this.totpConfirmButton.disabled = true;
    ManagementRestrictions.recordTotpMemoryCheckpoint("verify-click");
    try {
      const configured = await ManagementRestrictions.completeTotpSetup(
        setup.secret,
        setup.recoveryCode,
        this.totpCode.value
      );
      if (!configured) {
        this.totpError.textContent = await document.l10n.formatValue(
          "addons-admin-mode-totp-invalid-code"
        );
        this.totpError.hidden = false;
        return;
      }
      this.updateAdminModeToggle();
      this.totpRecovery.querySelector(
        ".admin-mode-totp-recovery-code"
      ).textContent = setup.recoveryCode;
      this.totpEnrollment.hidden = true;
      this.totpRecovery.hidden = false;
      this.pendingTotpSetup = null;
    } catch (error) {
      console.error("Could not set up Admin Mode authenticator", error);
      this.totpError.textContent = await document.l10n.formatValue(
        "addons-admin-mode-totp-setup-failed"
      );
      this.totpError.hidden = false;
    } finally {
      ManagementRestrictions.recordTotpMemoryCheckpoint("verify-finished");
      this.totpSubmitting = false;
      this.totpConfirmButton.disabled = false;
    }
  }
}
customElements.define("addon-page-header", AddonPageHeader);
