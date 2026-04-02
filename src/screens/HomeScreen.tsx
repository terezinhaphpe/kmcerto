import { useFocusEffect } from "@react-navigation/native";
import { router } from "expo-router";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ActivityIndicator, AppState, Platform, ScrollView, StyleSheet, Text, TouchableOpacity, View } from "react-native";

import { ScreenContainer } from "@/components/screen-container";
import { MetricCard } from "@/src/components/metric-card";
import { PermissionRow } from "@/src/components/permission-row";
import {
  getPermissionStatus,
  isMonitoringActive,
  openAccessibilitySettings,
  openBatteryOptimizationSettings,
  openOverlaySettings,
  startMonitoring,
  subscribeToOverlayUpdates,
  type KmCertoOverlayPayload,
} from "@/src/modules/KmCertoNative";
import { getMinimumPerKm } from "@/src/storage/kmcerto-storage";
import { formatCurrency, formatNumber } from "@/src/utils/kmcerto";

const SUPPORTED_APPS = ["iFood", "99", "Uber", "inDrive", "Lalamove"];

export default function HomeScreen() {
  const [loading, setLoading] = useState(true);
  const [overlayGranted, setOverlayGranted] = useState(false);
  const [accessibilityGranted, setAccessibilityGranted] = useState(false);
  const [batteryIgnored, setBatteryIgnored] = useState(false);
  const [monitoringActive, setMonitoringActive] = useState(false);
  const [minimumPerKm, setMinimumPerKm] = useState(1.5);
  const [lastOverlay, setLastOverlay] = useState<KmCertoOverlayPayload | null>(null);
  const appState = useRef(AppState.currentState);

  const refreshStatus = useCallback(async () => {
    setLoading(true);
    try {
      const [permissionStatus, minimum, monitoring] = await Promise.all([
        getPermissionStatus(),
        getMinimumPerKm(),
        isMonitoringActive(),
      ]);
      setOverlayGranted(permissionStatus.overlayGranted);
      setAccessibilityGranted(permissionStatus.accessibilityGranted);
      setBatteryIgnored(permissionStatus.batteryOptimizationIgnored);
      setMonitoringActive(monitoring);
      setMinimumPerKm(minimum);

      // AUTO-START: Se todas as permissões estão concedidas, iniciar monitoramento automaticamente
      if (permissionStatus.overlayGranted && permissionStatus.accessibilityGranted) {
        await startMonitoring();
        setMonitoringActive(true);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  // Escutar mudanças de estado do app (foreground/background)
  // para re-verificar permissões quando o usuário volta ao app
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (nextAppState) => {
      if (appState.current.match(/inactive|background/) && nextAppState === "active") {
        // App voltou ao foreground — re-verificar permissões
        void refreshStatus();
      }
      appState.current = nextAppState;
    });

    return () => subscription.remove();
  }, [refreshStatus]);

  useFocusEffect(
    useCallback(() => {
      void refreshStatus();
      const subscription = subscribeToOverlayUpdates((payload) => {
        setLastOverlay(payload);
      });

      return () => subscription.remove();
    }, [refreshStatus]),
  );

  const automaticModeAvailable = useMemo(() => Platform.OS === "android", []);
  const allPermissionsGranted = overlayGranted && accessibilityGranted;

  return (
    <ScreenContainer edges={["top", "left", "right", "bottom"]}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.heroCard}>
          <Text style={styles.eyebrow}>KmCerto</Text>
          <Text style={styles.heroTitle}>Overlay inteligente para motoboy com cálculo imediato de viabilidade.</Text>
          <Text style={styles.heroDescription}>
            O aplicativo monitora iFood, 99, Uber, inDrive e Lalamove no Android, extrai valor e distância automaticamente e compara o resultado com o seu valor mínimo por quilômetro.
          </Text>
          {monitoringActive && allPermissionsGranted ? (
            <View style={styles.activeIndicator}>
              <View style={styles.activeDot} />
              <Text style={styles.activeText}>Monitoramento ativo em segundo plano</Text>
            </View>
          ) : null}
        </View>

        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>Status operacional</Text>
            {loading ? <ActivityIndicator color="#F5D400" /> : null}
          </View>

          <PermissionRow
            title="Permissão de overlay"
            description="Necessária para exibir o cartão flutuante e a bolinha indicadora por cima dos aplicativos monitorados."
            enabled={overlayGranted}
          />
          <PermissionRow
            title="Serviço de acessibilidade"
            description="Necessário para ler o conteúdo da tela ativa e calcular os indicadores automaticamente."
            enabled={accessibilityGranted}
          />
          <PermissionRow
            title="Otimização de bateria desativada"
            description="Impede que o Android encerre o serviço em segundo plano. Essencial para funcionamento contínuo."
            enabled={batteryIgnored}
          />
          {monitoringActive ? (
            <PermissionRow
              title="Monitoramento"
              description="O serviço está rodando e detectando corridas automaticamente."
              enabled={true}
            />
          ) : (
            <PermissionRow
              title="Monitoramento"
              description="O monitoramento não está ativo. Ative todas as permissões acima."
              enabled={false}
            />
          )}
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Critério atual</Text>
          <View style={styles.metricsRow}>
            <MetricCard label="Mínimo por km" value={minimumPerKm} variant="per_km" />
            <MetricCard label="Apps suportados" value={SUPPORTED_APPS.length} variant="number" />
          </View>
          <Text style={styles.supportedApps}>Apps monitorados: {SUPPORTED_APPS.join(", ")}.</Text>
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Ações</Text>

          {!overlayGranted ? (
            <TouchableOpacity style={styles.primaryButton} onPress={() => void openOverlaySettings()} disabled={!automaticModeAvailable}>
              <Text style={styles.primaryButtonText}>Ativar permissão de overlay</Text>
            </TouchableOpacity>
          ) : null}

          {!accessibilityGranted ? (
            <TouchableOpacity style={styles.primaryButton} onPress={() => void openAccessibilitySettings()} disabled={!automaticModeAvailable}>
              <Text style={styles.primaryButtonText}>Ativar acessibilidade</Text>
            </TouchableOpacity>
          ) : null}

          {!batteryIgnored ? (
            <TouchableOpacity style={styles.warningButton} onPress={() => void openBatteryOptimizationSettings()} disabled={!automaticModeAvailable}>
              <Text style={styles.warningButtonText}>Desativar otimização de bateria</Text>
            </TouchableOpacity>
          ) : null}

          {allPermissionsGranted && !monitoringActive ? (
            <TouchableOpacity style={styles.primaryButton} onPress={async () => {
              await startMonitoring();
              setMonitoringActive(true);
            }}>
              <Text style={styles.primaryButtonText}>Iniciar monitoramento</Text>
            </TouchableOpacity>
          ) : null}

          <TouchableOpacity style={styles.secondaryButton} onPress={() => void refreshStatus()}>
            <Text style={styles.secondaryButtonText}>Atualizar status</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.secondaryButton} onPress={() => router.push("/manual") }>
            <Text style={styles.secondaryButtonText}>Abrir modo manual</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.secondaryButton} onPress={() => router.push("/settings") }>
            <Text style={styles.secondaryButtonText}>Abrir configurações</Text>
          </TouchableOpacity>
          {!automaticModeAvailable ? <Text style={styles.platformWarning}>O monitoramento automático por overlay e acessibilidade funciona apenas no Android.</Text> : null}
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Última leitura automática</Text>
          {lastOverlay ? (
            <View style={styles.lastResultCard}>
              <View style={styles.lastResultHeader}>
                <Text style={styles.lastResultLabel}>{lastOverlay.sourceApp ?? "Aplicativo monitorado"}</Text>
                <View
                  style={[
                    styles.statusBadge,
                    lastOverlay.status === "ACEITAR" ? styles.acceptBadge : styles.rejectBadge,
                  ]}
                >
                  <Text style={styles.statusBadgeText}>{lastOverlay.status ?? "Pendente"}</Text>
                </View>
              </View>
              <Text style={styles.lastFare}>{lastOverlay.totalFareLabel ?? (lastOverlay.totalFare != null ? formatCurrency(lastOverlay.totalFare) : "N/A")}</Text>
              <View style={styles.metricsRow}>
                <MetricCard label="R$/km" value={lastOverlay.perKm ?? null} variant="per_km" />
                <MetricCard label="R$/hr" value={lastOverlay.perHour ?? null} variant="per_hour" />
              </View>
              <View style={styles.metricsRow}>
                <MetricCard label="R$/min" value={lastOverlay.perMinute ?? null} variant="per_minute" />
                <MetricCard label="Mínimo usado" value={lastOverlay.minimumPerKm ?? minimumPerKm} variant="per_km" />
              </View>
            </View>
          ) : (
            <View style={styles.emptyState}>
              <Text style={styles.emptyStateText}>
                Quando o módulo nativo detectar uma oferta em um app suportado, o último resultado automático aparecerá aqui para conferência.
              </Text>
            </View>
          )}
        </View>

        <View style={styles.footerCard}>
          <Text style={styles.footerTitle}>Regra de decisão</Text>
          <Text style={styles.footerText}>
            Hoje o KmCerto considera <Text style={styles.footerStrong}>{formatNumber(minimumPerKm)} por km</Text> como linha mínima. Qualquer oferta igual ou acima desse valor deve ser marcada como <Text style={[styles.footerStrong, styles.acceptText]}>ACEITAR</Text>; abaixo disso, como <Text style={[styles.footerStrong, styles.rejectText]}>RECUSAR</Text>.
          </Text>
        </View>
      </ScrollView>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  content: {
    padding: 20,
    gap: 20,
    backgroundColor: "#101114",
  },
  heroCard: {
    backgroundColor: "#1D2026",
    borderRadius: 24,
    padding: 20,
    gap: 12,
    borderWidth: 1,
    borderColor: "#2D313A",
  },
  eyebrow: {
    color: "#F5D400",
    fontSize: 13,
    fontWeight: "800",
    letterSpacing: 1,
    textTransform: "uppercase",
  },
  heroTitle: {
    color: "#FFFFFF",
    fontSize: 28,
    lineHeight: 35,
    fontWeight: "800",
  },
  heroDescription: {
    color: "#CFCFD4",
    fontSize: 15,
    lineHeight: 22,
  },
  activeIndicator: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    backgroundColor: "#16A34A20",
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 8,
    marginTop: 4,
  },
  activeDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: "#16A34A",
  },
  activeText: {
    color: "#16A34A",
    fontSize: 13,
    fontWeight: "700",
  },
  section: {
    gap: 12,
  },
  sectionHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  sectionTitle: {
    color: "#FFFFFF",
    fontSize: 18,
    fontWeight: "800",
  },
  metricsRow: {
    flexDirection: "row",
    gap: 12,
    flexWrap: "wrap",
  },
  supportedApps: {
    color: "#CFCFD4",
    fontSize: 13,
    lineHeight: 19,
  },
  primaryButton: {
    backgroundColor: "#F5D400",
    borderRadius: 18,
    paddingVertical: 16,
    paddingHorizontal: 18,
    alignItems: "center",
  },
  primaryButtonText: {
    color: "#101114",
    fontSize: 16,
    fontWeight: "800",
  },
  warningButton: {
    backgroundColor: "#DC2626",
    borderRadius: 18,
    paddingVertical: 16,
    paddingHorizontal: 18,
    alignItems: "center",
  },
  warningButtonText: {
    color: "#FFFFFF",
    fontSize: 16,
    fontWeight: "800",
  },
  secondaryButton: {
    backgroundColor: "#1D2026",
    borderRadius: 18,
    paddingVertical: 16,
    paddingHorizontal: 18,
    alignItems: "center",
    borderWidth: 1,
    borderColor: "#2D313A",
  },
  secondaryButtonText: {
    color: "#FFFFFF",
    fontSize: 15,
    fontWeight: "700",
  },
  platformWarning: {
    color: "#F5D400",
    fontSize: 13,
    lineHeight: 19,
  },
  lastResultCard: {
    backgroundColor: "#1D2026",
    borderRadius: 24,
    padding: 18,
    gap: 12,
    borderWidth: 1,
    borderColor: "#2D313A",
  },
  lastResultHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    gap: 12,
  },
  lastResultLabel: {
    color: "#CFCFD4",
    fontSize: 14,
    fontWeight: "700",
    flex: 1,
  },
  statusBadge: {
    borderRadius: 999,
    paddingHorizontal: 12,
    paddingVertical: 7,
  },
  acceptBadge: {
    backgroundColor: "#16A34A",
  },
  rejectBadge: {
    backgroundColor: "#DC2626",
  },
  statusBadgeText: {
    color: "#FFFFFF",
    fontSize: 12,
    fontWeight: "800",
  },
  lastFare: {
    color: "#FFFFFF",
    fontSize: 30,
    fontWeight: "800",
  },
  emptyState: {
    backgroundColor: "#1D2026",
    borderRadius: 24,
    borderWidth: 1,
    borderColor: "#2D313A",
    padding: 18,
  },
  emptyStateText: {
    color: "#CFCFD4",
    fontSize: 14,
    lineHeight: 20,
  },
  footerCard: {
    backgroundColor: "#15171C",
    borderRadius: 24,
    padding: 18,
    gap: 10,
    borderWidth: 1,
    borderColor: "#2D313A",
  },
  footerTitle: {
    color: "#FFFFFF",
    fontSize: 16,
    fontWeight: "800",
  },
  footerText: {
    color: "#CFCFD4",
    fontSize: 14,
    lineHeight: 21,
  },
  footerStrong: {
    color: "#FFFFFF",
    fontWeight: "800",
  },
  acceptText: {
    color: "#16A34A",
  },
  rejectText: {
    color: "#DC2626",
  },
});
