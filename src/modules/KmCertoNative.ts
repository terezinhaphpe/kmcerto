import { Platform } from "react-native";

import KmCertoNativeModule from "kmcerto-native";
import type { KmCertoOverlayEventPayload } from "kmcerto-native/src/KmCertoNative.types";

export type KmCertoPermissionStatus = {
  overlayGranted: boolean;
  accessibilityGranted: boolean;
};

export type KmCertoOverlayPayload = {
  totalFare?: number;
  totalFareLabel?: string;
  status?: "ACEITAR" | "RECUSAR";
  statusColor?: string;
  perKm?: number;
  perHour?: number | null;
  perMinute?: number | null;
  minimumPerKm?: number;
  sourceApp?: string;
  rawText?: string;
};

function normalizeOverlayPayload(payload: KmCertoOverlayEventPayload | KmCertoOverlayPayload): KmCertoOverlayPayload {
  return {
    totalFare: payload.totalFare,
    totalFareLabel: payload.totalFareLabel,
    status: payload.status,
    statusColor: payload.statusColor,
    perKm: payload.perKm,
    perHour: payload.perHour ?? null,
    perMinute: payload.perMinute ?? null,
    minimumPerKm: payload.minimumPerKm,
    sourceApp: payload.sourceApp,
    rawText: payload.rawText,
  };
}

async function callBooleanMethod(method: keyof Pick<
  typeof KmCertoNativeModule,
  | "isOverlayPermissionGranted"
  | "isAccessibilityServiceEnabled"
  | "openOverlaySettings"
  | "openAccessibilitySettings"
  | "startMonitoring"
  | "stopMonitoring"
  | "hideOverlay"
>) {
  if (Platform.OS !== "android") {
    return false;
  }

  try {
    const result = await KmCertoNativeModule[method]();
    return Boolean(result);
  } catch (e) {
    console.warn(`KmCertoNative.${method} failed:`, e);
    return false;
  }
}

export async function getPermissionStatus(): Promise<KmCertoPermissionStatus> {
  if (Platform.OS !== "android") {
    return { overlayGranted: false, accessibilityGranted: false };
  }

  const [overlayGranted, accessibilityGranted] = await Promise.all([
    callBooleanMethod("isOverlayPermissionGranted"),
    callBooleanMethod("isAccessibilityServiceEnabled"),
  ]);

  return { overlayGranted, accessibilityGranted };
}

export function openOverlaySettings() {
  return callBooleanMethod("openOverlaySettings");
}

export function openAccessibilitySettings() {
  return callBooleanMethod("openAccessibilitySettings");
}

export function startMonitoring() {
  return callBooleanMethod("startMonitoring");
}

export function stopMonitoring() {
  return callBooleanMethod("stopMonitoring");
}

export function hideOverlay() {
  return callBooleanMethod("hideOverlay");
}

export async function setNativeMinimumPerKm(value: number) {
  if (Platform.OS !== "android") {
    return false;
  }

  try {
    return Boolean(await KmCertoNativeModule.setMinimumPerKm(value));
  } catch {
    return false;
  }
}

export async function getNativeMinimumPerKm() {
  if (Platform.OS !== "android") {
    return 1.5;
  }

  try {
    return Number(await KmCertoNativeModule.getMinimumPerKm()) || 1.5;
  } catch {
    return 1.5;
  }
}

export function showTestOverlay(payload?: KmCertoOverlayPayload) {
  if (Platform.OS !== "android") {
    return Promise.resolve(false);
  }

  return KmCertoNativeModule.showTestOverlay(JSON.stringify(payload ?? {})).catch(() => false);
}

export function subscribeToOverlayUpdates(listener: (payload: KmCertoOverlayPayload) => void) {
  if (Platform.OS !== "android") {
    return { remove: () => undefined };
  }

  const subscription = KmCertoNativeModule.addListener("KmCertoOverlayData", (payload: KmCertoOverlayEventPayload) => {
    listener(normalizeOverlayPayload(payload));
  });

  return {
    remove: () => subscription.remove(),
  };
}
