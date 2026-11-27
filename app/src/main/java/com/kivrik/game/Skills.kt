package com.kivrik.game

/** Yetenek ağacındaki tek bir düğüm. x/y düzen hesabıyla doldurulur. */
class Skill(
    val id: String,
    val name: String,
    val branch: String,
    val icon: String,
    val parent: String?,
    val costs: List<Long>,
    val desc: String
) {
    val maxLevel: Int get() = costs.size
    var x = 0f
    var y = 0f
    /** Düğümde gösterilen kısa ad (parantez içi kaldırılır). */
    val shortName: String get() = name.replace(Regex("\\s*\\(.*\\)"), "")
}

object Tree {
    private const val BR_A = "Sol Dal • Yön & Hayatta Kalma"
    private const val BR_B = "Saldırı Dalı • Diş & Hasar"
    private const val BR_C = "Orta Dal • Elma & Zenginlik"
    private const val BR_D = "Fırtına Dalı • Yıldırım"
    private const val BR_E = "Sağ Dal • Kolay Oynanış & Hız"
    private const val BR_F = "Şans Dalı • Hazine & Ekonomi"

    private fun sk(id: String, name: String, branch: String, icon: String, parent: String?, costs: List<Int>, desc: String) =
        Skill(id, name, branch, icon, parent, costs.map { it.toLong() }, desc)

    // Sıra önemli: aynı ebeveynin çocukları soldan sağa bu sırayla dizilir.
    val ALL: List<Skill> = listOf(
        sk("right", "Sağa Sürünme (→)", "Kök Yetenek", "➡️", null, listOf(0),
            "Yılanın sağa doğru neşeyle sürünmesini sağlar. Her maceranın başlangıcı!"),
        sk("down", "Aşağı Dönüş (↓)", "Temel Hareket", "⬇️", "right", listOf(1),
            "Aşağı doğru kıvrılmayı açar! Bu yeteneği açınca yetenek ağacı yeni dallara budaklanır."),

        // ===== A: YÖN & HAYATTA KALMA =====
        sk("up", "Yukarı Dönüş (↑)", BR_A, "⬆️", "down", listOf(2),
            "Yukarı doğru gidebilmeyi açar. Artık bahçede dikey manevra yapabilirsin!"),
        sk("left", "Sola Dönüş (←)", BR_A, "⬅️", "up", listOf(4),
            "Sola gitmeyi açarak tam 4 yön kontrolü kazanırsın!"),
        sk("wallCushion", "Duvar Yastığı", BR_A, "🛡️", "left", listOf(10),
            "Çite çarpınca ölmez, otomatik güvenli yöne dönersin! Tur başına tek hakkın vardır (Hazırlık Çantası ile artar)."),
        sk("shockCushion", "Yastık Şoku", BR_A, "🔔", "wallCushion", listOf(150, 400, 1000),
            "Duvar Yastığı çalışınca sahadaki tüm elmalara 2 / 4 / 6 hasar verir."),
        sk("startKit", "Hazırlık Çantası", BR_A, "🎒", "shockCushion", listOf(300, 900, 2500, 6000, 14000),
            "Yastık, portal geçiş ve hayalet kuyruğun tur başına hak sayısı: 1 / 2 / 3 / 4 / 5. Her biri kendi hakkını harcar. Anka Kuşu ayrıdır."),
        sk("wallWrap", "Duvardan Geçiş", BR_A, "🌀", "wallCushion", listOf(40),
            "Ahşap çitlerden portal gibi geçip karşı taraftan çıkarsın! Tur başına tek hakkın vardır (Hazırlık Çantası ile artar)."),
        sk("portalLoot", "Portal Ganimeti", BR_A, "💰", "wallWrap", listOf(250, 700, 1800),
            "Portaldan her geçişte küçük bir elma ödülü kazanırsın (seviyeyle artar)."),
        sk("ghostTail", "Hayalet Kuyruk", BR_A, "👻", "wallWrap", listOf(60),
            "Kendi kuyruğundan şeffaf geçebilirsin (kısa süre dokunulmazlık)! Tur başına tek hakkın vardır (Hazırlık Çantası ile artar)."),
        sk("ghostFeast", "Hayalet Ziyafeti", BR_A, "🍽️", "ghostTail", listOf(300, 800, 2000),
            "Hayalet modundayken yediğin elmalar %50 / %100 / %150 daha değerli olur."),
        sk("cushionRegen", "Yenilenen Yastık", BR_A, "🧯", "ghostTail", listOf(120, 300, 700),
            "Her 20 / 16 / 12 elmada 1 yastık hakkı geri gelir (hak üst sınırını geçmez)."),
        sk("portalRegen", "Portal Yenileme", BR_A, "🔮", "cushionRegen", listOf(150, 400, 900),
            "Her 30 / 24 / 18 elmada 1 portal hakkı geri gelir (hak üst sınırını geçmez)."),
        sk("phoenix", "Anka Kuşu", BR_A, "🔥", "portalRegen", listOf(15000),
            "Ölümcül çarpışmada küllerinden bir kez doğarsın! Tur başına sadece 1 can hakkı verir."),

        // ===== B: SALDIRI (DİŞ & HASAR) =====
        sk("sharpTeeth", "Keskin Diş", BR_B, "🦷", "down", listOf(20, 60, 160, 400, 900),
            "Elmalar artık canlı! Her ısırığının hasarı +1 artar. Elmalar tur ilerledikçe sertleşir."),
        sk("critBite", "Kritik Isırık", BR_B, "💥", "sharpTeeth", listOf(80, 220, 500, 1100),
            "Isırığın %6 / %12 / %18 / %24 şansla 3 kat hasar vurur."),
        sk("executioner", "Cellat", BR_B, "🪓", "critBite", listOf(300, 800, 1800),
            "Isırıktan sonra canı %10 / %20 / %30 altına düşen elma anında yutulur."),
        sk("venom", "Zehirli Diş", BR_B, "☠️", "sharpTeeth", listOf(100, 260, 600, 1400),
            "Isırdığın elma zehirlenir ve 4 / 6 / 8 / 10 adım boyunca her adımda 1 can kaybeder."),
        sk("explosive", "Patlayıcı Elma", BR_B, "🧨", "venom", listOf(500, 1300, 3000),
            "Yutulan elma patlar! Çevresindeki elmalara 1 / 2 / 3 hasar verir."),
        sk("bodyBite", "Gövde Isırığı", BR_B, "🐛", "sharpTeeth", listOf(150, 400, 1000),
            "Gövdenin ilk 3 / 6 / 9 parçası da üzerinden geçtiği elmalara hasar verir."),
        sk("softApples", "Yumuşak Elmalar", BR_B, "🍑", "bodyBite", listOf(700, 1600, 3600, 8000),
            "Elmalar tur içinde çok daha geç sertleşir (elma canı artışı yavaşlar)."),

        // ===== C: ELMA & ZENGİNLİK =====
        sk("moreApples", "Bereketli Bahçe", BR_C, "🍏", "down", listOf(2, 4, 8, 14, 22),
            "Sahada aynı anda bulunan elma sayısını seviye başına +1 artırır."),
        sk("doubleTaste", "Çifte Lezzet", BR_C, "🍒", "moreApples", listOf(10, 18, 28, 42),
            "Yediğin elmanın değerini ikiye katlama şansı verir (+%8 / seviye)."),
        sk("splitApple", "Çoğalan Elma", BR_C, "🧬", "doubleTaste", listOf(200, 600, 1500),
            "Yutulan elma %4 / %8 / %12 şansla 2 yeni elmaya bölünür."),
        sk("appleValue", "Sulu Elmalar", BR_C, "🍎", "moreApples", listOf(3, 6, 12, 20, 32),
            "Her elmanın verdiği değeri seviye başına +1 artırır. Çok daha hızlı zengin ol!"),
        sk("goldenApple", "Altın Elma", BR_C, "🌟", "appleValue", listOf(10, 20, 35),
            "5 kat değerinde, 2 kat canlı parlayan Altın Elma çıkma şansı ekler (%3 / %6 / %9)."),
        sk("magnet", "Elma Mıknatısı", BR_C, "🧲", "goldenApple", listOf(18, 32, 50),
            "Çekim alanına giren elmalar yılana doğru kayar ve her adımda senin ısırık hasarının %10 / %25 / %50'si kadar (en az 1, yukarı yuvarlanır) hasar alır; canı biten elma yutulur! Çekim alanı: 1 / 1,5 / 2 kare."),
        sk("magnetPower", "Güçlü Mıknatıs", BR_C, "🔋", "magnet", listOf(200, 600, 1500),
            "Mıknatısın her adımda verdiği hasar +1 / +2 / +3 artar."),
        sk("goldenMagnet", "Altın Çekim", BR_C, "🧿", "magnetPower", listOf(350, 1000),
            "Mıknatıs, altın ve elmas elmaları %50 / %100 daha uzaktan yakalar."),
        sk("appleRain", "Elma Yağmuru", BR_C, "🌧️", "magnet", listOf(40, 75),
            "Her 8 elma yediğinde gökten bahçeye ekstra elmalar yağar! (+3 / +6 elma)"),
        sk("comboChain", "Kombo Zinciri", BR_C, "🔗", "appleRain", listOf(60, 100, 150),
            "Elmaları peş peşe hızlı yersen 3. elmadan itibaren her kombo basamağında bonus kazanırsın."),
        sk("comboSaver", "Kombo Koruyucu", BR_C, "⏱️", "comboChain", listOf(150, 450, 1100),
            "Kombo süren 4 / 8 / 12 adım uzar, zincir daha zor kopar."),
        sk("goldenValue", "Altın Dokunuş", BR_C, "👑", "comboSaver", listOf(90, 140, 200),
            "Altın Elmaların değeri seviye başına +2 kat daha artar (5x → 7x → 9x → 11x)."),
        sk("goldRush", "Altın Hücum", BR_C, "🏅", "goldenValue", listOf(400, 1200, 3000),
            "Altın elma yedikten sonraki 3 / 5 / 7 elma 2 kat değerli olur."),
        sk("consolation", "Teselli Ödülü", BR_C, "🎁", "goldenValue", listOf(100, 150, 220),
            "Çarpıp turu bitirince o turdaki kazancının %12 / %24 / %36'sı kadar ekstra elma alırsın."),
        sk("globalBoost", "Usta Bahçıvan", BR_C, "🧑‍🌾", "consolation", listOf(1500, 4000, 9000, 20000, 42000),
            "Tüm elma kazançların seviye başına %15 artar."),
        sk("autopilot", "Otopilot", BR_C, "🤖", "globalBoost", listOf(40000, 120000, 300000),
            "Oyunun son yeteneği! Sen yön vermezken yılan en yakın elmaya kendi gider (4 kare / 8 kare / her yerden). Tuşa basınca yönetim yine sende."),

        // ===== D: FIRTINA (YILDIRIM) =====
        sk("lightning", "Yıldırım", BR_D, "⚡", "down", listOf(60, 160, 400, 900, 2000),
            "Gökten rastgele bir elmaya yıldırım düşer! Aralık: 45 / 36 / 29 / 23 / 18 adım. Hasar: 2 / 3 / 4 / 5 / 6."),
        sk("chainLightning", "Zincir Şimşek", BR_D, "🌩️", "lightning", listOf(300, 800, 2000),
            "Yıldırım yakındaki +1 / +2 / +3 elmaya daha atlar (hasarın %60'ı)."),
        sk("lightningBounty", "Şimşek Bereketi", BR_D, "🎆", "chainLightning", listOf(600, 1500, 3500),
            "Yıldırımla yutulan elmalar %25 / %50 / %75 daha değerlidir."),
        sk("stormCharge", "Fırtına Şarjı", BR_D, "🔌", "lightning", listOf(400, 1100, 2600),
            "Altın elma yediğinde 1 / 2 / 3 ekstra yıldırım düşer."),

        // ===== E: KOLAY OYNANIŞ & HIZ =====
        sk("speedGear", "Hız Vitesi", BR_E, "⚡", "down", listOf(3, 8, 16),
            "Yılan hızını artırır ve oyun altında Hız Seçici (Yavaş / Normal / Turbo) açar! Turbo modunda elmalar %10 / %20 / %50 daha değerli olur."),
        sk("shortTail", "Kısa Kuyruk", BR_E, "✂️", "speedGear", listOf(6, 14, 26),
            "Elma yediğinde kuyruğun daha yavaş uzar (her 2/3/4 elmada 1 uzar). Oynamak çok rahatlar!"),
        sk("wideField", "Geniş Çayır", BR_E, "🌻", "shortTail", listOf(12, 28),
            "Oyun ızgarasını büyüterek daha ferah ve rahat bir bahçe açar."),
        sk("tailTrim", "Kuyruk Makası", BR_E, "🪚", "wideField", listOf(120, 260, 500),
            "Altın elma yediğinde kuyruğun 1 / 2 / 3 kare kısalır (en az 4 kare kalır)."),
        sk("slowMo", "Ağır Çekim", BR_E, "🐌", "tailTrim", listOf(30, 55, 85),
            "Elma yedikten sonra yılan kısa süre yavaşlar, böylece dönüş yapmak kolaylaşır (2 / 4 / 6 adım)."),

        // ===== F: ŞANS & HAZİNE =====
        sk("luckyClover", "Dört Yapraklı Yonca", BR_F, "🍀", "down", listOf(60, 200, 600),
            "Altın elma, Elmas elma, kritik ve çifte lezzet şansların %5 / %10 / %20 artar."),
        sk("gemApple", "Elmas Elma", BR_F, "💎", "luckyClover", listOf(250, 700, 1800, 4500),
            "Sahada %1 / %2 / %3 / %4 şansla 20 kat değerli, 3 kat canlı Elmas Elma çıkar."),
        sk("harvestMoon", "Hasat Ayı", BR_F, "🌕", "gemApple", listOf(500, 1400, 3500),
            "Her 25. elmada o elmanın değerinin 3 / 6 / 9 katı kadar bonus kazanırsın."),
        sk("interest", "Faiz", BR_F, "🏦", "harvestMoon", listOf(1200, 3200, 8000, 18000),
            "Her tur başında bankandaki elmaların %0,4 / %0,8 / %1,2 / %1,6'sı kadar faiz alırsın (üst sınırı var)."),
        sk("discount", "Esnaf İndirimi", BR_F, "🏷️", "interest", listOf(2500, 6000, 13000, 26000, 50000),
            "Tüm yeteneklerin bedeli seviye başına %4 ucuzlar (en fazla %20)."),
        sk("orchard", "Fidan", BR_F, "🌳", "luckyClover", listOf(120, 350, 900),
            "Bahçede zamanla kendiliğinden ekstra elma biter (20 / 15 / 10 adımda bir)."),
        sk("longBody", "Uzun Gövde", BR_F, "📏", "orchard", listOf(300, 800, 2000),
            "Kuyruğunun her parçası için elma değerine %1 / %2 / %3 bonus gelir (en çok 60 parça).")
    )

    val MAP: Map<String, Skill> = ALL.associateBy { it.id }

    var worldW = 1400f
    var worldH = 1600f

    init {
        // Her yaprak kendi sütununu alır, ebeveyn çocuklarının ortasında durur.
        val kids = HashMap<String, MutableList<Skill>>()
        for (s in ALL) s.parent?.let { kids.getOrPut(it) { ArrayList() }.add(s) }
        val slot = 150f
        val row = 150f
        val top = 100f
        val left = 100f
        var nextSlot = 0
        var maxY = 0f
        fun place(node: Skill, depth: Int) {
            val ch = kids[node.id] ?: emptyList<Skill>()
            node.y = top + depth * row
            maxY = maxOf(maxY, node.y)
            if (ch.isEmpty()) {
                node.x = left + nextSlot * slot
                nextSlot++
            } else {
                for (c in ch) place(c, depth + 1)
                node.x = (ch.first().x + ch.last().x) / 2f
            }
        }
        place(MAP.getValue("right"), 0)
        worldW = left * 2 + (nextSlot - 1) * slot
        worldH = maxY + 170f
    }
}
