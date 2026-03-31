import { EventEmitter, type EventSubscription } from "expo-modules-core";
import type { KmCertoOverlayEventPayload } from "./KmCertoNative.types";

let NativeModule: any = null;
let emitter: EventEmitter | null = null;

try {
  const { requireNativeModule } = require("expo-modules-core");
  NativeModule = requireNativeModule("KmCertoNative");
  emitter = new EventEmitter(NativeModule);
} catch (e) {
  console.warn("KmCertoNative module not available:", e);
}

const KmCertoNativeModule = {
  isOverlayPermissionGranted: (): Promise<boolean> => {
    if (!NativeModule) return Promise.resolve(false);
    try {
      return NativeModule.isOverlayPermissionGranted();
    } catch {
      return Promise.resolve(false);
    }
  },
  isAccessibilityServiceEnabled: (): Promise<boolean> => {
    if (!NativeModule) return Promise.resolve(false);
    try {
      return NativeModule.isAccessibilityServiceEnabled();
    } catch {
      return Promise.resolve(false);
    }
  },
  openOverlaySettings: (): Promise<boolean> => {
    if (!NativeModule) return Promise.resolve(false);
    try {
      return NativeModule.openOverlaySettings();
    } catch {
      return Promise.resolve(false);
    }
  },
  openAccessibilitySettings: (): Promise<boolean> => {
    if (!NativeModule) return Promise.resolve(false);
    try {
      return NativeModule.openAccessibilitySettings();
    } catch {
      return Promise.resolve(false);
    }
  },
  startMonitoring: (): Promise<boolean> => {
    if (!NativeModule) return Promise.resolve(false);
    try {
      return NativeModule.startMonitoring();
    } catch {
      return Promise.resolve(false);
    }
  },
  stopMonitoring: (): Promise<boolean> => {
    if (!NativeModule) return Promise.resolve(false);
    try {
      return NativeModule.stopMonitoring();
    } catch {
      return Promise.resolve(false);
    }
  },
  hideOverlay: (): Promise<boolean> => {
    if (!NativeModule) return Promise.resolve(false);
    try {
      return NativeModule.hideOverlay();
    } catch {
      return Promise.resolve(false);
    }
  },
  setMinimumPerKm: (value: number): Promise<boolean> => {
    if (!NativeModule) return Promise.resolve(false);
    try {
      return NativeModule.setMinimumPerKm(value);
    } catch {
      return Promise.resolve(false);
    }
  },
  getMinimumPerKm: (): Promise<number> => {
    if (!NativeModule) return Promise.resolve(1.5);
    try {
      return NativeModule.getMinimumPerKm();
    } catch {
      return Promise.resolve(1.5);
    }
  },
  showTestOverlay: (payload: string): Promise<boolean> => {
    if (!NativeModule) return Promise.resolve(false);
    try {
      return NativeModule.showTestOverlay(payload);
    } catch {
      return Promise.resolve(false);
    }
  },
  addListener: (
    event: "KmCertoOverlayData",
    listener: (payload: KmCertoOverlayEventPayload) => void,
  ): EventSubscription => {
    if (!emitter) return { remove: () => {} } as EventSubscription;
    return emitter.addListener(event, listener);
  },
};

export default KmCertoNativeModule;
