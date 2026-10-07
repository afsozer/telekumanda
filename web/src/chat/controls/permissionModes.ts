// İzin kipi seçenekleri ve etiketleri. Bileşen dosyasında değil burada durur —
// oxlint'in react/only-export-components kuralı bileşen dosyasından işlev
// dışa aktarımını istemez.
// Etiketler BackendLabels.kt'teki permissionModeLabel ile birebir.

/** Akış meta.permissionModes boş geldiğinde kullanılan varsayılan liste (BackendOptions.kt). */
export const DEFAULT_PERMISSION_MODES = ['', 'auto', 'plan', 'acceptEdits', 'bypassPermissions']

export function permissionModeLabel(mode: string): string {
  switch (mode) {
    case '':
    case 'default':
      return 'Normal'
    case 'plan':
      return 'Plan'
    case 'acceptEdits':
    case 'accept_edits':
      return 'Düzenlemeleri otomatik kabul'
    case 'bypassPermissions':
      return 'İzinleri atla'
    case 'auto':
      return 'Otomatik'
    case 'dontAsk':
    case 'dont_ask':
      return 'Sorma'
    default:
      return mode
  }
}
