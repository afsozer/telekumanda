// Köprü kimlik doğrulaması.
//
// Köprü token'ı iki yerden kabul ediyor (server.mjs:134): `Authorization: Bearer`
// başlığı VEYA `?token=` sorgu parametresi. İkisi de gerekli:
//
// - REST çağrıları başlığı kullanır (token adres çubuğuna ve geçmişe düşmez).
// - WebSocket sorgu parametresini kullanmak ZORUNDA, çünkü tarayıcı WebSocket
//   el sıkışmasına özel başlık ekleyemez. Köprüde `?token=` desteği olmasaydı
//   web istemcisi akışa hiç bağlanamazdı.
//
// İlk açılışta token adresten gelir (`/ui/?token=...`), yerel depoya alınır ve
// adresten SİLİNİR — yoksa tarayıcı geçmişinde kalıcı olarak dururdu.

const STORAGE_KEY = 'agentbridge.token'

/** Sorgu dizesinden token'ı ayıklar. Saf fonksiyon; testte doğrudan çağrılır. */
export function tokenFromSearch(search: string): string | null {
  const value = new URLSearchParams(search).get('token')
  const trimmed = value?.trim()
  return trimmed ? trimmed : null
}

/**
 * Token'ı adresten temizlenmiş hâliyle geri döndürür — yani `token` parametresi
 * çıkarılmış yeni sorgu dizesini. Diğer parametreler korunur.
 * Sorguda token yoksa `null` döner (adrese dokunmaya gerek yok demektir).
 */
export function searchWithoutToken(search: string): string | null {
  const params = new URLSearchParams(search)
  if (!params.has('token')) return null
  params.delete('token')
  const rest = params.toString()
  return rest ? `?${rest}` : ''
}

export function storedToken(): string | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value && value.trim() ? value : null
  } catch {
    // Depolama kapalıysa (gizli sekme, üçüncü taraf çerez engeli) uygulama
    // token giriş ekranıyla çalışmaya devam etsin — çökmesin.
    return null
  }
}

export function saveToken(token: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, token.trim())
  } catch {
    /* yoksay: aşağıdaki bellek kopyası oturum boyunca yeter */
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    /* yoksay */
  }
}

/**
 * Açılış akışı: adreste token varsa onu al, kaydet ve adresten temizle;
 * yoksa yerel depodakini kullan.
 */
export function bootstrapToken(location: Location, history: History): string | null {
  const fromUrl = tokenFromSearch(location.search)
  if (fromUrl) {
    saveToken(fromUrl)
    const cleaned = searchWithoutToken(location.search)
    if (cleaned !== null) {
      history.replaceState(null, '', `${location.pathname}${cleaned}${location.hash}`)
    }
    return fromUrl
  }
  return storedToken()
}
