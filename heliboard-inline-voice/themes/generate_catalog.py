#!/usr/bin/env python3
"""Maintain the curated gallery. Each row is an intentionally complete appearance, not a Cartesian product."""
import json
from pathlib import Path

# name | Arabic | background | keys | function keys | labels | accent | gradient end | artwork | pattern
PALETTES = {
"dark": [
"Obsidian|سبج|111319|242832|1B1E27|F1F4FF|A8C7FA|111319|97A6CC|solid",
"Midnight Sea|بحر منتصف الليل|071D2D|143448|0E293C|E0F5FF|70D9F5|0A243D|70D9F5|waves",
"Graphite|جرافيت|1B1C1F|303238|25272B|F3F3F5|E3AD70|1B1C1F|93979F|stripes",
"Blackberry|توت داكن|211327|39233F|2D1934|FFEAFD|F7ABEA|110D20|CE8FDB|dots",
"Burgundy Night|ليلة عنابية|290F1C|492535|391B29|FFEAF1|FFB0C2|120E19|D98EA5|rings",
"Deep Teal|فيروز داكن|082925|18433E|10372F|E1FFF4|79E7B8|061D20|7BB8AE|fern",
"Storm Blue|أزرق العاصفة|161F35|293953|21304A|EDF3FF|A8C7FF|0C1523|91B0E3|rain",
"Cosmic Charcoal|فحم كوني|171526|2C2943|211F35|F0ECFF|D0BCFF|0A0B16|D0BCFF|stars",
],
"light": [
"Porcelain|خزف|E8EDF2|FFFFFF|D6DEE8|172B42|275E9A|E8EDF2|456789|solid",
"Powder Blue|أزرق ناعم|DBEDFA|F8FCFF|C7E1F4|153951|146180|CDE6F7|548BB0|waves",
"Peach Cream|كريمة الخوخ|FBE5D7|FFFAF4|F0D1BB|53372D|884927|F5DAC7|C38262|dots",
"Mint Sorbet|نعناع مثلج|D9EEE4|F9FFFC|BFE2D0|1C4438|226649|D0E8D8|5A9977|leaves",
"Arctic Snow|ثلج القطب|EAF5FA|FFFFFF|D1E8F1|213D50|285D7F|DBEFF7|76ACC1|diamonds",
"Lemon Linen|كتان الليمون|F4EFCF|FFFDF1|E5DDB1|4B4326|706015|EFE7BF|BDA750|stripes",
"Lilac Morning|صباح الليلك|EDE3F7|FEFAFF|DACBEA|443054|704998|E3D8F0|A486BC|rings",
"Warm Cloud|سحابة دافئة|EEE9E2|FFFDFA|DED7CC|453E36|755738|E5DFD4|A99C8C|solid",
],
"gold": [
"Royal Gold|ذهب ملكي|080808|202020|141414|F4D88A|EAC764|18130A|D8AF47|stripes",
"Art Deco|آرت ديكو|100D09|2B251B|201A12|FBE3A8|E7BA58|171209|D9B36A|diamonds",
"Golden Circuit|دوائر ذهبية|080D0C|1B2925|13201B|E9D8A9|DFBB63|0B1713|D4A94D|circuit",
"Champagne Black|أسود شمبانيا|111113|28272B|1D1C20|EEE2C4|E3CE99|18151B|D6C18E|dots",
"Gilded Night|ليل مذهّب|0D101B|21283C|172032|E8DABB|CBAA65|070A13|E9CF8F|stars",
"Antique Brass|نحاس عتيق|1C120A|392A1C|2B1F14|F5D9AF|D7AC70|0E0A06|C18D49|rings",
"Gold Horizon|أفق ذهبي|141009|30271B|221B12|FFE7AF|E8C675|30210C|E1B858|dunes",
"Onyx Geometry|هندسة العقيق|050708|1C2328|11191D|F0DAA6|DDB966|101719|C3A55F|hex",
],
"neon": [
"Electric Cyan|سماوي كهربائي|061820|103A46|0A2A35|D9FBFF|63ECFF|050C15|00E5FF|grid",
"Laser Magenta|أرجوان ليزر|1B0820|42133F|300D30|FFE5FF|FF9CEF|0D071A|FF38D1|stripes",
"Acid Lime|ليموني نيون|101C09|293C17|1D2D10|ECFFD5|C1F668|071109|ADFF2F|circuit",
"Cyber Violet|بنفسجي سيبراني|120A2C|2C1D53|21123F|F2E8FF|D9BCFF|080C23|AF70FF|triangles",
"Neon Coral|مرجاني نيون|240C12|4E202C|381421|FFECEF|FFB1A8|120A19|FF6175|waves",
"Plasma Blue|أزرق البلازما|071226|142D53|0D2144|E5F2FF|A7D3FF|080B22|4F9FFF|rings",
"Mint Voltage|جهد النعناع|071C19|123D32|0D2E27|DDFFF0|88FFD0|051111|3DFFC0|diamonds",
"Ultraviolet Rose|وردي فوق بنفسجي|200B24|47203F|33152F|FFEAF8|F8BAE2|0D1031|E060CC|stars",
],
"gradient": [
"Desert Sunset|غروب الصحراء|5D2032|402536|311B2B|FFF0E8|FFC79B|D4774C|FFD4A8|gradient",
"Aurora Sky|سماء الشفق|0A2147|183F58|102E44|E4FCFF|A0E5DF|116B69|8CE9BC|aurora",
"Blue Hour|الساعة الزرقاء|10264B|263E66|1C2E51|EDF5FF|ACD5FA|5B4787|D3BEFF|gradient",
"Mango Dawn|فجر المانجو|F4D4A5|FFF7E5|EAC291|573A22|815026|F6A58A|B76E44|gradient",
"Lavender Mist|ضباب الخزامى|D7D1F4|F7F3FF|C6BCE4|3D3156|67449A|E9CBDF|9678BD|aurora",
"Ocean Depths|أعماق المحيط|07314A|14516A|0B3D54|E3FAFF|98E2F1|07182E|6BCAD5|gradient",
"Rose Quartz|كوارتز وردي|F0D3DF|FFF6FA|E3BECE|5E3447|873D60|DED0F0|C18EA6|gradient",
"Ember Flow|تدفق الجمر|30101A|4D2528|39171E|FFE6D5|F9C19B|904326|E7A16A|waves",
],
"minimal": [
"Paper|ورق|F4F3EF|FFFFFF|E3E1DC|202020|46515B|F4F3EF|737373|solid",
"Ink|حبر|111111|252525|1C1C1C|F5F5F5|DDDDDD|111111|777777|solid",
"Sage Quiet|هدوء المريمية|E0E7DE|F5F8F3|CCD7C9|28382C|45624C|E0E7DE|748675|solid",
"Fog|ضباب|DDE1E5|F7F8FA|C5CBD2|24303E|425E80|DDE1E5|75869B|solid",
"Soft Clay|طين ناعم|EBDDD3|FCF5F0|D7C2B2|4D3428|77513B|EBDDD3|AF8B72|solid",
"Navy Silence|سكون كحلي|111E2B|213448|182A3B|E9F1F8|AEC9DC|111E2B|799CB8|solid",
"Charcoal Lines|خطوط فحمية|232323|393939|2C2C2C|F2EDE4|D4C8B0|232323|BDB6AC|stripes",
"Sandstone|حجر رملي|E3D8BE|F5EEDC|CFC1A2|403822|726137|E3D8BE|AD9B71|solid",
],
"nature": [
"Forest Canopy|مظلة الغابة|0D2C20|244B35|183B28|EBF7D9|B6D38A|163C29|7CA96B|leaves",
"Bamboo Grove|بستان الخيزران|DDE6BD|F6F8E7|C4D29A|344626|5B702D|C6D4A6|7B954B|bamboo",
"Emerald Fern|سرخس زمردي|072D2B|1B4D47|113D38|DFFFF4|8DDEBD|0C4940|70BA99|fern",
"Coral Coast|ساحل المرجان|CFE9E9|F5FFFF|B0D7D8|1F4750|286A75|B4D4DD|78B1B5|waves",
"Alpine Ridge|قمة الألب|152B3A|2D4652|223A45|F0F7F1|C8DAB2|314B4D|91AEA3|mountains",
"Sahara Dunes|كثبان الصحراء|B88C5F|EEDBC1|DEC6A4|4E3526|704C2E|80583D|F7DBAA|dunes",
"Rain Garden|حديقة المطر|233C43|3D5960|2D484F|E6F8F6|A5DBD6|152E36|82BFC0|rain",
"Northern Lights|الأضواء الشمالية|0C1D2F|1D3B49|132D3D|E0F8F6|A0E8C9|17413D|B7D6FE|aurora",
],
"flowers": [
"Rose Garden|حديقة الورد|3D1527|603048|4C2338|FFEAF2|F3BBCF|25111F|E39BB9|flowers",
"Cherry Blossom|زهر الكرز|F0DCE4|FFFAFD|DFBBCB|5B3145|854363|E8CAD6|BD7698|sakura",
"Lavender Fields|حقول الخزامى|332640|51405F|41314F|F7EFFF|D6BCE7|211B35|B79ACE|flowers",
"Golden Daisy|أقحوان ذهبي|EDE3BA|FFFCEE|DCCC96|4A3D21|746018|DBCE9B|BDA34E|daisy",
"Lotus Pond|بركة اللوتس|103E3D|285A55|1D4B47|EEFFF4|E2B5CD|172E42|D6A3C3|lotus",
"Jasmine Night|ليل الياسمين|192627|304142|243436|F7F5DA|E6DCAB|0E191E|E8E2C6|daisy",
"Hibiscus|كركديه|4B1721|71313D|5B2330|FFF0ED|FFC5B3|321523|EFA38E|flowers",
"Bluebell|أجراس زرقاء|D4DFF2|F5F9FF|BACAE8|2E405F|405E8E|BFCBE6|819BC9|sakura",
],
"geometry": [
"Honeycomb|قرص العسل|28301C|424D2B|343F21|F3F6D9|DED68F|17261E|C2CB70|hex",
"Prism|منشور|18223C|303E5B|24314D|EDF1FF|BFC9F5|27213F|AA9CCB|triangles",
"Diamond Slate|ألماس أردوازي|D8E3E5|F8FDFD|C1D2D6|2B444A|396A77|C5D9DD|7197A2|diamonds",
"Orbital|مدارات|2B1935|4A3157|3C2648|FAEDFF|E8C2FA|18192F|C69DD7|rings",
"Linear Copper|خطوط نحاسية|30221D|4D382E|3D2C24|FFEBDD|E9C3A5|211B1A|BA8C6B|stripes",
"Polka Mist|نقاط ضبابية|E3E1EE|FCFAFF|CECBDF|3D3854|655B86|D5D4E4|9289AE|dots",
"Blueprint|مخطط هندسي|092C50|1A4672|113B64|E1F1FF|B0D7FF|0D2540|7BAFDE|grid",
"Terracotta Mosaic|فسيفساء فخارية|D5AB96|F6E8DB|C4967C|4E3028|7B4130|BE8971|9E6550|triangles",
],
"code": [
"Terminal Green|طرفية خضراء|061109|14291B|0C1E12|BCF6C8|9BE4AB|08170D|54A76B|terminal",
"Midnight IDE|محرر منتصف الليل|151A24|282F40|1F2634|E7EAF5|C6BEF0|11141D|9988B9|circuit",
"Ocean Editor|محرر المحيط|082734|164251|103440|DFF8F9|97E1DF|061C2A|63A9B9|terminal",
"Amber Console|وحدة كهرمانية|181207|302512|241C0C|FFE4AC|F6CC80|0F0E06|B79950|grid",
"Matrix Lines|خطوط المصفوفة|0B1910|1E3424|14281A|DDFFE0|ADE8AD|071009|4E935C|rain",
"Paper Editor|محرر ورقي|EEECE2|FFFEF7|D8D6C7|353C35|4B6555|E1E0D5|8D9A84|terminal",
"Violet Brackets|أقواس بنفسجية|1C1330|38264E|2B1C3E|F5EAFE|E0BEF6|151025|B48BCF|terminal",
"Solder Board|لوحة إلكترونية|112E27|284C3E|1C3C32|F1F3D8|E1D49A|0C211D|99B789|circuit",
],
}

def generate():
    themes = []
    for category, rows in PALETTES.items():
        assert len(rows) == 8
        for index, row in enumerate(rows):
            name, ar, bg, keys, function, label, accent, secondary, decor, pattern = row.split("|")
            entry = dict(id=category + "_" + name.lower().replace(" ", "_"), name=name, nameAr=ar,
                category=category, background="#" + bg, keys="#" + keys, functional="#" + function,
                label="#" + label, accent="#" + accent, secondary="#" + secondary,
                decoration="#" + decor, pattern=pattern,
                style="Material" if category == "minimal" or category == "code" and index in (0, 3, 4) else "Rounded",
                borders=category != "minimal" or index == 6)
            themes.append(entry)
    assert len(themes) == 80
    target = Path(__file__).parent / "assets/compact_themes.json"
    target.write_text(json.dumps(themes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(themes)} distinct themes in {len(PALETTES)} categories ({target.stat().st_size} bytes)")

if __name__ == "__main__":
    generate()
