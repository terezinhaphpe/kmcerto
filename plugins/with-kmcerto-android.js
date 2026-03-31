const { withAndroidManifest, withDangerousMod } = require("@expo/config-plugins");
const fs = require("fs");
const path = require("path");

/**
 * Conteúdo do XML de configuração do AccessibilityService.
 * Define quais eventos o serviço monitora e quais pacotes são filtrados.
 */
const ACCESSIBILITY_SERVICE_CONFIG_XML = `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagIncludeNotImportantViews|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100"
    android:packageNames="br.com.ifood.driver.app,com.app99.driver,com.ubercab.driver"
    android:description="@string/kmcerto_accessibility_description"
    android:settingsActivity="expo.modules.kmcertonative.MainActivity" />
`;

/**
 * String de descrição para o serviço de acessibilidade (exibida nas configurações do Android).
 */
const ACCESSIBILITY_DESCRIPTION =
  "KmCerto monitora ofertas de corrida nos apps iFood, 99 e Uber para calcular automaticamente o valor por quilômetro.";

function withKmCertoManifest(config) {
  return withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults;
    const app = manifest.manifest.application[0];

    // Ensure permissions
    if (!manifest.manifest["uses-permission"]) {
      manifest.manifest["uses-permission"] = [];
    }
    const permissions = manifest.manifest["uses-permission"];
    const requiredPermissions = [
      "android.permission.SYSTEM_ALERT_WINDOW",
      "android.permission.FOREGROUND_SERVICE",
      "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
      "android.permission.WAKE_LOCK",
    ];
    for (const perm of requiredPermissions) {
      const exists = permissions.some(
        (p) => p.$ && p.$["android:name"] === perm,
      );
      if (!exists) {
        permissions.push({ $: { "android:name": perm } });
      }
    }

    // Ensure service array exists
    if (!app.service) app.service = [];

    // Remove any existing declarations to avoid duplicates, then re-add
    const overlayServiceName =
      "expo.modules.kmcertonative.KmCertoOverlayService";
    const accessibilityServiceName =
      "expo.modules.kmcertonative.KmCertoAccessibilityService";

    app.service = app.service.filter(
      (s) =>
        s.$ &&
        s.$["android:name"] !== overlayServiceName &&
        s.$["android:name"] !== accessibilityServiceName,
    );

    // Add overlay service
    app.service.push({
      $: {
        "android:name": overlayServiceName,
        "android:exported": "false",
        "android:foregroundServiceType": "specialUse",
      },
      property: [
        {
          $: {
            "android:name": "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
            "android:value": "overlay",
          },
        },
      ],
    });

    // Add accessibility service with foregroundServiceType for keep-alive
    app.service.push({
      $: {
        "android:name": accessibilityServiceName,
        "android:exported": "true",
        "android:label": "KmCerto",
        "android:permission": "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android:foregroundServiceType": "specialUse",
      },
      "intent-filter": [
        {
          action: [
            {
              $: {
                "android:name":
                  "android.accessibilityservice.AccessibilityService",
              },
            },
          ],
        },
      ],
      "meta-data": [
        {
          $: {
            "android:name": "android.accessibilityservice",
            "android:resource": "@xml/kmcerto_accessibility_service_config",
          },
        },
      ],
      property: [
        {
          $: {
            "android:name": "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
            "android:value": "accessibility_monitoring",
          },
        },
      ],
    });

    return cfg;
  });
}

/**
 * Usa withDangerousMod para criar os arquivos de recurso XML necessários
 * que o AndroidManifest referencia:
 *   - res/xml/kmcerto_accessibility_service_config.xml
 *   - res/values/kmcerto_strings.xml (para a description do serviço)
 */
function withKmCertoResources(config) {
  return withDangerousMod(config, [
    "android",
    async (cfg) => {
      const projectRoot = cfg.modRequest.projectRoot;
      const resDir = path.join(
        projectRoot,
        "android",
        "app",
        "src",
        "main",
        "res",
      );

      // ── 1. Criar res/xml/kmcerto_accessibility_service_config.xml ──
      const xmlDir = path.join(resDir, "xml");
      if (!fs.existsSync(xmlDir)) {
        fs.mkdirSync(xmlDir, { recursive: true });
      }
      fs.writeFileSync(
        path.join(xmlDir, "kmcerto_accessibility_service_config.xml"),
        ACCESSIBILITY_SERVICE_CONFIG_XML,
        "utf-8",
      );

      // ── 2. Criar/atualizar res/values/kmcerto_strings.xml ──
      //    (para a android:description do serviço de acessibilidade)
      const valuesDir = path.join(resDir, "values");
      if (!fs.existsSync(valuesDir)) {
        fs.mkdirSync(valuesDir, { recursive: true });
      }
      const stringsContent = `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="kmcerto_accessibility_description">${ACCESSIBILITY_DESCRIPTION}</string>
</resources>
`;
      fs.writeFileSync(
        path.join(valuesDir, "kmcerto_strings.xml"),
        stringsContent,
        "utf-8",
      );

      return cfg;
    },
  ]);
}

module.exports = function withKmcertoAndroid(config) {
  config = withKmCertoResources(config);
  config = withKmCertoManifest(config);
  return config;
};
