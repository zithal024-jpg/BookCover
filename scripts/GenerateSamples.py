"""Reproducible, original EPUB 3 fixtures. Run with Python + Pillow; no downloaded text/art."""
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED, ZIP_DEFLATED, ZipInfo
from xml.sax.saxutils import escape
import io
import re
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'app/src/main/assets/samples'
DEST.mkdir(parents=True, exist_ok=True)
FONT = Path('C:/Windows/Fonts')

def cover(title, subtitle, background, accent, kind):
    im = Image.new('RGB', (720, 1080), background)
    d = ImageDraw.Draw(im)
    d.rectangle((35, 35, 685, 1045), outline=accent, width=2)
    d.text((360, 120), 'BOOKCOVER ORIGINALS', font=ImageFont.truetype(str(FONT/'segoeui.ttf'), 22), fill=accent, anchor='mm')
    if kind == 'lantern':
        d.polygon([(360, 275), (440, 340), (280, 340)], fill=accent)
        d.rounded_rectangle((295, 340, 425, 520), 10, outline=accent, width=8)
        d.ellipse((334, 380, 386, 450), fill='#F4DA9B')
        d.line((360, 340, 360, 520), fill=accent, width=5)
        d.arc((327, 240, 393, 310), 180, 360, fill=accent, width=6)
        d.line((130, 572, 590, 572), fill=accent, width=2)
    else:
        for i in range(7):
            coords = [(180 + i*42 + int(32*__import__('math').sin(y/90+i)), y) for y in range(240, 575, 4)]
            d.line(coords, fill=accent, width=3)
    y = 690
    for line in title.split('|'):
        d.text((360,y), line, font=ImageFont.truetype(str(FONT/'georgia.ttf'), 54), fill='#FAF0D8', anchor='mm')
        y += 72
    d.text((360, 925), subtitle, font=ImageFont.truetype(str(FONT/'segoeui.ttf'), 25), fill=accent, anchor='mm')
    out=io.BytesIO(); im.save(out, 'PNG'); return out.getvalue()

def write_epub(filename, title, chapters, art):
    def entry(z, name, data, compress=ZIP_DEFLATED):
        info=ZipInfo(name, (2026,10,26,0,0,0)); info.compress_type=compress
        z.writestr(info, data)
    with ZipFile(DEST/filename, 'w') as z:
        entry(z, 'mimetype', 'application/epub+zip', ZIP_STORED)
        entry(z, 'META-INF/container.xml', '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        items=''.join(f'<item id="c{i}" href="chapter{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(chapters)))
        spine=''.join(f'<itemref idref="c{i}"/>' for i in range(len(chapters)))
        entry(z, 'EPUB/book.opf', f'''<?xml version="1.0" encoding="UTF-8"?><package version="3.0" unique-identifier="uid" xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:bookcover:{filename}</dc:identifier><dc:title>{escape(title)}</dc:title><dc:creator>BookCover Originals</dc:creator><dc:language>en</dc:language><dc:rights>Original sample text and artwork created for BookCover. CC0.</dc:rights><meta property="dcterms:modified">2026-10-26T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/><item id="css" href="style.css" media-type="text/css"/>{items}</manifest><spine>{spine}</spine></package>''')
        links=''.join(f'<li><a href="chapter{i}.xhtml">{escape(heading)}</a></li>' for i,(heading,_) in enumerate(chapters))
        entry(z, 'EPUB/nav.xhtml', f'<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><h1>Contents</h1><ol>{links}</ol></nav></body></html>')
        entry(z, 'EPUB/style.css', 'body {font-family:serif; line-height:1.5;} h1 {font-size:1.6em; font-weight:normal;} p {text-indent:1.2em; margin:0 0 0.7em;}')
        entry(z, 'EPUB/cover.png', art)
        for i,(heading,paras) in enumerate(chapters):
            content=''.join(f'<p>{escape(p)}</p>' for p in paras)
            entry(z, f'EPUB/chapter{i}.xhtml', f'<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>{escape(heading)}</title><link rel="stylesheet" href="style.css"/></head><body><h1>{escape(heading)}</h1>{content}</body></html>')

source=(ROOT/'app/src/main/java/com/bookcover/diagnostic/reader/SampleBook.kt').read_text(encoding='utf-8')
lantern=re.findall(r'"""(.*?)"""', source, re.S)
assert len(lantern)==12
write_epub('lantern-keeper.epub', 'The Lantern Keeper', [('The Light in the Window',lantern[:4]),('A Map of Reading Rooms',lantern[4:8]),('The Next Keeper',lantern[8:])], cover('The Lantern|Keeper','A STORY OF SHARED LIGHT','#203F3D','#C5AC77','lantern'))

river=[
('The Folded Map', [
    'On the first clear morning of autumn, Eli found a map tucked into the pocket of his late aunt’s raincoat. It showed the river that crossed their town, but none of the roads had names. Instead, small notes followed the water: a place to listen, a place to wait, a place to begin again.',
    'He carried it to the bridge before breakfast. The river looked ordinary there, brown beneath the stone arch and bright where it caught the sky. A cyclist passed without looking down. Eli had crossed this bridge for twenty years and could not remember the last time he had stopped in its middle.',
    'The first note pointed toward the mill. Beside its locked gate, a narrow path led between nettles to a low bank. Someone had set a plank across two stones. He sat, folded the map along its old creases, and listened to water slipping under the wheel.',
    'After a while he heard other things: a spoon against a cup in the nearest house, a dog shaking its collar, the short complaint of a rook. His aunt had lived in that house. He wondered whether she had made this map for herself or for someone she hoped would slow down enough to follow it.',
    'A woman emerged from the mill carrying a bucket of brushes. She introduced herself as Noor, the new sign painter. When Eli showed her the map, she recognized the handwriting at once. Every Thursday, his aunt had brought her a pot of tea and an impossible question about the color of water.',
    'Noor invited him inside. There were signs for shops that had not yet opened and one for a boat that had not yet been built. On a shelf stood a blue teapot. The river, she said, changed its color according to what stood beside it. Perhaps people did the same.',
]),
('The Long Way Home', [
    'They followed the second note after lunch. It led beyond the allotments, where the footpath became grass and the town fell quiet behind them. A line of poplars marked a bend. Beneath the trees, an old ferry landing reached into the shallows like an unfinished sentence.',
    'A fisherman was repairing a chair at the landing. He had no fishing rod with him. Waiting was a useful skill, he told them, but it was easier to practice when one had something small to mend. He turned the chair over and offered Eli the screwdriver.',
    'The repair took longer than either of them expected. One screw had lost its thread, another was the wrong size, and the seat needed a strip of wood trimmed from a discarded crate. Noor held the pieces together while Eli worked. The fisherman told them stories of the ferry in winter.',
    'When the chair finally stood level, they placed it at the end of the landing. The fisherman sat down with the satisfaction of a man arriving exactly on time. Eli glanced at his watch, then put it in his pocket. There was no appointment left to hurry toward.',
    'On the back of the map, a faint pencil line showed a path along the far bank. Noor suggested they return that way. The nearest crossing was another mile upstream. Eli almost objected, then remembered that the map had never promised a short journey.',
    'They walked until the town appeared again between the trees. From the opposite bank the mill seemed smaller and the bridge more generous. Familiar places had rearranged themselves. He understood then why his aunt had left the roads unnamed: a good walk did not always need a destination.',
]),
('A New Line', [
    'At the last bend, they found a bench facing away from the river. It looked toward a neglected orchard. The final note said only: begin here. Eli turned the paper over twice, expecting another instruction. Noor sat on the bench and waited for him to join her.',
    'The orchard belonged to the town, she explained. Volunteers had once picked its apples for the school. Now the gate had fallen from its hinges and the grass stood high beneath the branches. A few bright fruits lay where they had dropped, unremarked and uncollected.',
    'Eli had expected his aunt’s map to lead to something she had hidden. Instead it had brought him to things that needed attention: a chair, a conversation, an orchard. He took a pencil from Noor and drew a small square beside the bench. Under it he wrote: bring a basket.',
    'The following Saturday, six people came. The fisherman arrived with his repaired chair, Noor with a painted sign, and the schoolteacher with three impatient children. They propped the gate against a tree and began with the nearest branches. Nobody asked who was in charge.',
    'By evening the baskets were full. Eli pinned a copy of the map beside the new sign, leaving a wide margin for other hands. Someone marked a quiet place to see kingfishers. A child added a tree that looked like a horse. Noor drew a teapot beside the mill.',
    'He kept the original in his aunt’s raincoat. Sometimes he followed its route, sometimes he took a path that was not on it yet. The river continued through the town as it always had. What had changed was the number of places where he now knew to stop.',
])]
write_epub('river-maps.epub', 'Maps of the River', river, cover('Maps of|the River','A STORY OF FINDING TIME','#303C59','#B8B8A1','river'))
print('Generated', *(p.name for p in sorted(DEST.glob('*.epub'))))
