const { withAndroidManifest, withDangerousMod, withPlugins } = require("@expo/config-plugins");
const fs = require("fs");
const path = require("path");

const withKmCertoManifest = (config) => {
  return withAndroidManifest(config, async (cfg) => {
    let androidManifest = cfg.modResults.manifest;
    const mainApplication = androidManifest.application[0];

    // Adicionar permissões necessárias
    const permissions = [
      "android.permission.SYSTEM_ALERT_WINDOW",
      "android.permission.FOREGROUND_SERVICE",
      "android.permission.WAKE_LOCK",
      "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    ];

    if (!androidManifest["uses-permission"]) androidManifest["uses-permission"] = [];
    
    permissions.forEach((perm) => {
      if (!androidManifest["uses-permission"].find((p) => p.$["android:name"] === perm)) {
        androidManifest["uses-permission"].push({ $: { "android:name": perm } });
      }
    });

    // Limpar serviços antigos para evitar duplicidade
    if (mainApplication.service) {
      mainApplication.service = mainApplication.service.filter(
        (s) => s.$ && s.$["android:name"] && !s.$["android:name"].includes("KmCerto")
      );
    } else {
      mainApplication.service = [];
    }

    // Adicionar KmCertoAccessibilityService
    mainApplication.service.push({
      $: {
        "android:name": "expo.modules.kmcertonative.KmCertoAccessibilityService",
        "android:permission": "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android:exported": "true",
      },
      "intent-filter": [
        {
          action: [{ $: { "android:name": "android.accessibilityservice.AccessibilityService" } }],
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
    });

    // Adicionar KmCertoPermissionActivity
    if (!mainApplication.activity) mainApplication.activity = [];
    const hasPermActivity = mainApplication.activity.some(
      (a) => a.$ && a.$["android:name"] === "expo.modules.kmcertonative.KmCertoPermissionActivity"
    );
    if (!hasPermActivity) {
      mainApplication.activity.push({
        $: {
          "android:name": "expo.modules.kmcertonative.KmCertoPermissionActivity",
          "android:exported": "false",
          "android:theme": "@android:style/Theme.Translucent.NoTitleBar",
        },
      });
    }

    return cfg;
  });
};

const withKmCertoResources = (config) => {
  return withDangerousMod(config, [
    "android",
    async (cfg) => {
      const projectRoot = cfg.modRequest.projectRoot;
      const resDir = path.join(projectRoot, "android/app/src/main/res");

      // Criar diretório xml se não existir
      const xmlDir = path.join(resDir, "xml");
      if (!fs.existsSync(xmlDir)) fs.mkdirSync(xmlDir, { recursive: true });

      // Escrever configuração do Accessibility Service
      const accessibilityConfig = `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowContentChanged|typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagIncludeNotImportantViews|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="150"
    android:description="@string/kmcerto_accessibility_description" />`;

      fs.writeFileSync(path.join(xmlDir, "kmcerto_accessibility_service_config.xml"), accessibilityConfig);

      // Escrever strings necessárias
      const valuesDir = path.join(resDir, "values");
      if (!fs.existsSync(valuesDir)) fs.mkdirSync(valuesDir, { recursive: true });

      const stringsPath = path.join(valuesDir, "kmcerto_strings.xml");
      const stringsContent = `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="kmcerto_accessibility_description">O KmCerto usa acessibilidade para ler as ofertas de corridas e calcular o valor por KM automaticamente.</string>
</resources>`;

      fs.writeFileSync(stringsPath, stringsContent);

      return cfg;
    },
  ]);
};

module.exports = (config) => {
  return withPlugins(config, [withKmCertoManifest, withKmCertoResources]);
};
