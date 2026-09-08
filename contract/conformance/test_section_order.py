"""C1 regression: real schema validation, including empty optional sections."""
import json
import unittest
import run


class SectionOrderTest(unittest.TestCase):
    def problems(self, names):
        schema = run.load_schema()
        diag = {k: ([] if k == "warnings" else 0)
                for k in schema["$defs"]["diag"]["required"]}
        h = dict(bsi=1, kind="response", id="order", method="bsi.solve", revision=1,
                 status="ok", diag=diag, buckling=dict(kind="none", state="disabled-by-request"),
                 unassigned=[], sections=[dict(name=n, offset=0, bytes=0, count=0) for n in names])
        return run.check_reply(schema, run.Validator(schema), "bsi.solve", run.Reply(json.dumps(h), b""), 0)

    def test_legacy_order(self):
        self.assertEqual([], self.problems(["blocks", "members", "memberBlocks", "stations:f32", "attrsEcho"]))

    def test_geometry_between_stations_and_facets(self):
        self.assertEqual([], self.problems(["blocks", "members", "memberBlocks", "stations:f32", "memberGeometry", "facets", "facetSurfaces", "facetBlocks"]))
        self.assertTrue(any("fixed order" in p for p in self.problems(["memberGeometry", "stations"])))

    def test_facet_blocks_after_surfaces(self):
        for surface in ["facetSurfaces", "facetSurfaces:f32"]:
            self.assertEqual([], self.problems(["blocks", "facets", surface, "facetBlocks", "attrsEcho"]))

    def test_misplaced_and_duplicate_sections(self):
        for names in [["blocks", "facets", "facetBlocks", "facetSurfaces"],
                      ["blocks", "facets", "facetSurfaces", "attrsEcho", "facetBlocks"],
                      ["blocks", "facetBlocks", "facetBlocks"], ["members", "blocks"]]:
            self.assertTrue(any("fixed order" in p for p in self.problems(names)))


if __name__ == "__main__":
    unittest.main()
