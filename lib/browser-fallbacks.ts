export type TextShare = (data: { text: string }) => Promise<void>;

export async function shareReminderMessage(
  message: string,
  share: TextShare,
  onShared: () => void | Promise<void>,
): Promise<"shared" | "cancelled"> {
  try {
    await share({ text: message });
    await onShared();
    return "shared";
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") return "cancelled";
    throw error;
  }
}

export async function copyReminderMessage(
  message: string,
  clipboardWriteText?: (text: string) => Promise<void>,
  fallbackCopy?: () => boolean,
): Promise<"copied" | "manual"> {
  if (clipboardWriteText) {
    try {
      await clipboardWriteText(message);
      return "copied";
    } catch {
      // Continue to the selectable-text fallback.
    }
  }
  return fallbackCopy?.() ? "copied" : "manual";
}

export function supportsFileShare(
  navigatorLike: { share?: unknown; canShare?: (data: { files: File[] }) => boolean } | undefined,
  file: File,
): boolean {
  if (!navigatorLike || typeof navigatorLike.share !== "function") return false;
  if (!navigatorLike.canShare) return true;
  try {
    return navigatorLike.canShare({ files: [file] });
  } catch {
    return false;
  }
}
