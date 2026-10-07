import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import verify
from shadow_evidence import validate_shadow_evidence, validate_shadow_negative


class ShadowEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.checks = [self.check(phase, scene) for phase in ('before-reload', 'after-reload') for scene in ('lit', 'caster', 'restored')]

    def check(self, phase, scene):
        caster = scene == 'caster'
        value = 0.5 - 2 / 64 if caster else 0.5 + 3 / 64
        filename = f'{phase}-{scene}.png'
        (self.root / filename).write_bytes(b'fixture')
        return dict(phase=phase, scene=scene, screenshot=filename, width=800 if phase == 'before-reload' else 960,
                    height=600, center_green=0 if caster else 1, center_blue=1 if caster else 0,
                    center_red=0, side_green=1, receiver_distance=7, accepted=True,
                    shadow=dict(observation=f'{phase}:{scene}', observations=5, resolution=256, sample_count=25,
                                samples=[value]*25, inherited_clipping_restored=True, negative_control=False,
                                terrain_region_setups=5, terrain_draw_states={"draw": {"clipDistanceEnabled": False, "probeOutput": 1}}))

    def write(self, checks=None, last=None):
        (self.root / 'shadow-evidence.json').write_text(json.dumps(dict(renderer='iris-active', pack='ip-shadow-fixture-v1',
                                                                       checks=self.checks if checks is None else checks, last_probe=last or {})))

    def test_shadow_fixture_stages_separately_from_clipping_fixture(self):
        client = self.root / 'client'
        with patch.multiple(verify, CLIENT_DIR=client, RESULT_DIR=self.root):
            evidence = verify.stage_shader_fixture('iris-active', shadow_fixture=True)
        self.assertTrue(evidence['shadow_fixture'])
        self.assertEqual(evidence['name'], 'ip-shadow-fixture-v1')
        self.assertIn('shadow', evidence['programs'])
        self.assertIn('shadow.enabled=true', (client / 'shaderpacks/ip-shadow-fixture-v1/shaders/shaders.properties').read_text())
        self.assertIn('gl_ClipDistance[0] = ip_ShadowClipProbe', (client / 'shaderpacks/ip-shadow-fixture-v1/shaders/shadow.vsh').read_text())
        self.assertIn('shaderPack=ip-shadow-fixture-v1', (client / 'config/iris.properties').read_text())

    def test_positive_requires_each_fresh_scene_and_actual_depth_pixels(self):
        self.write()
        validate_shadow_evidence(self.root, 'iris-active')
        for change in ('missing', 'stale', 'empty', 'blank', 'state', 'draw-bit'):
            altered = copy.deepcopy(self.checks)
            if change == 'missing': altered.pop()
            if change == 'stale': altered[-1]['shadow']['observation'] = 'before-reload:restored'
            if change == 'empty': altered[1]['shadow']['samples'] = [1]*25
            if change == 'blank': altered[1]['center_blue'] = 0
            if change == 'state': altered[1]['shadow']['inherited_clipping_restored'] = False
            if change == 'draw-bit': altered[1]['shadow']['terrain_draw_states']['draw']['clipDistanceEnabled'] = True
            self.write(altered)
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                validate_shadow_evidence(self.root, 'iris-active')

    def test_negative_requires_lit_control_and_removed_shadow_only(self):
        failed = copy.deepcopy(self.checks[1])
        failed.update(accepted=False, center_green=1, center_blue=0)
        failed['shadow'].update(samples=[1]*25, negative_control=True, terrain_draw_states={'draw': {'clipDistanceEnabled': True, 'probeOutput': -1}})
        self.write([self.checks[0]], failed)
        validate_shadow_negative(self.root)
        failed['receiver_distance'] = 1000
        self.write([self.checks[0]], failed)
        with self.assertRaises(RuntimeError): validate_shadow_negative(self.root)
        self.write([], failed)
        with self.assertRaises(RuntimeError): validate_shadow_negative(self.root)


if __name__ == '__main__': unittest.main()
