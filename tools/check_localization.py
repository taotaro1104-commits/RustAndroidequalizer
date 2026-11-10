"""Validate Android's public locale configuration and translated format arguments."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'android/app/src/main'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def catalog(directory):
    result = {}
    for path in directory.glob('*.xml'):
        for node in ET.parse(path).getroot():
            if node.tag in {'string', 'plurals'}:
                name = (node.tag, node.attrib['name'])
                assert name not in result, f'Duplicate string: {name}'
                if node.attrib.get('translatable') != 'false':
                    result[name] = ({'value': ''.join(node.itertext())} if node.tag == 'string' else
                                    {item.attrib['quantity']: ''.join(item.itertext()) for item in node})
    return result


class LocalizationContract(unittest.TestCase):
    def test_os_app_language_settings_offer_english_and_japanese(self):
        application = ET.parse(ROOT / 'AndroidManifest.xml').find('application')
        reference = application.attrib.get(ANDROID + 'localeConfig', '')
        self.assertTrue(reference.startswith('@xml/'), 'App languages must be discoverable in Android settings')
        locales = ET.parse(ROOT / 'res/xml' / (reference.split('/')[-1] + '.xml')).getroot()
        self.assertEqual({'en', 'ja'}, {node.attrib[ANDROID + 'name'] for node in locales})

    def test_both_languages_have_ui_and_compatible_format_arguments(self):
        english = catalog(ROOT / 'res/values')
        japanese = catalog(ROOT / 'res/values-ja')
        self.assertTrue(english, 'English is the fallback UI language')
        self.assertTrue(japanese, 'Japanese UI translations are required')
        self.assertEqual(set(english), set(japanese))
        specifier = re.compile(r'%(?:(\d+)\$)?[-+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])')
        for name in english:
            with self.subTest(string=name):
                reference = english[name]['other' if name[0] == 'plurals' else 'value']
                if name[0] == 'plurals':
                    self.assertIn('one', english[name], 'English must handle singular quantities')
                    self.assertIn('other', japanese[name])
                for translation in [*english[name].values(), *japanese[name].values()]:
                    self.assertEqual(sorted(specifier.findall(reference)), sorted(specifier.findall(translation)))
                for translation in english[name].values():
                    self.assertFalse(re.search(r'[ぁ-んァ-ヶ一-龯]', translation), 'Fallback UI must be English')


if __name__ == '__main__':
    unittest.main()
