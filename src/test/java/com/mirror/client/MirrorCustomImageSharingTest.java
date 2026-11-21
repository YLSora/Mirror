package com.mirror.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorCustomImageSharingTest {
    @Test
    void scansDhGenericAndFinalButExcludesShadowPrograms() {
        assertFalse(MirrorCustomImageSharing.scansProgram("Dh", "DhShadow"));
        assertTrue(MirrorCustomImageSharing.scansProgram("Dh", "DhGeneric"));
        assertTrue(MirrorCustomImageSharing.scansProgram("Dh", "DhTerrain"));
        assertTrue(MirrorCustomImageSharing.scansProgram("Dh", "DhWater"));
        assertFalse(MirrorCustomImageSharing.scansProgram("Shadow", "ShadowSolid"));
        assertFalse(MirrorCustomImageSharing.scansProgram("ShadowComposite", "ShadowComposite"));
        assertTrue(MirrorCustomImageSharing.scansProgram("Final", "Final"));
    }

    @Test
    void acceptsUniformDeclarationsAndReadOnlyImageBuiltins() {
        var decisions = MirrorCustomImageSharing.scan(List.of(new MirrorCustomImageSharing.Source("composite2",
                "layout(binding=0) uniform readonly image2D img;\n"
                        + "vec4 c = imageLoad ( img , ivec2(0) );\n"
                        + "ivec2 s = imageSize(img);")), Set.of("img"));

        assertTrue(decisions.get("img").readOnly());
    }

    @Test
    void rejectsWritesAssignmentsAndFunctionArgumentsWithFirstProgramReason() {
        var decisions = MirrorCustomImageSharing.scan(List.of(
                new MirrorCustomImageSharing.Source("prepare", "uniform image2D img;"),
                new MirrorCustomImageSharing.Source("deferred4", "imageStore(img, ivec2(0), vec4(0));")), Set.of("img"));

        assertFalse(decisions.get("img").readOnly());
        assertTrue(decisions.get("img").reason().contains("deferred4"));
        assertTrue(decisions.get("img").reason().contains("imageStore"));

        assertFalse(MirrorCustomImageSharing.scan(List.of(new MirrorCustomImageSharing.Source("begin",
                "img = otherImage;")), Set.of("img")).get("img").readOnly());
        assertFalse(MirrorCustomImageSharing.scan(List.of(new MirrorCustomImageSharing.Source("begin",
                "consume(img);")), Set.of("img")).get("img").readOnly());
        assertFalse(MirrorCustomImageSharing.scan(List.of(new MirrorCustomImageSharing.Source("begin",
                "void takeImage(image2D img) {}")), Set.of("img")).get("img").readOnly());
    }

    @Test
    void ignoresCommentsAndMatchesWholeNamesNotSimilarPrefixes() {
        var decisions = MirrorCustomImageSharing.scan(List.of(new MirrorCustomImageSharing.Source("gbuffers_terrain",
                "// imageStore(imgLpv1, p, v);\n"
                        + "/* imgLpv10 = value; */\n"
                        + "uniform image2D imgLpv1;\n"
                        + "imageLoad(imgLpv10, p);")), Set.of("imgLpv1", "imgLpv10"));

        assertTrue(decisions.get("imgLpv1").readOnly());
        assertTrue(decisions.get("imgLpv10").readOnly());
    }

    @Test
    void treatsMacroReferencesAndTokenConcatenationAsWrites() {
        assertFalse(MirrorCustomImageSharing.scan(List.of(new MirrorCustomImageSharing.Source("composite",
                "#define READ_IMAGE imageLoad(img, p)\n")), Set.of("img")).get("img").readOnly());

        var decisions = MirrorCustomImageSharing.scan(List.of(new MirrorCustomImageSharing.Source("setup",
                "#define IMAGE_NAME(prefix) prefix ## Lpv1\n")), Set.of("imgLpv1", "other"));
        assertFalse(decisions.get("imgLpv1").readOnly());
        assertTrue(decisions.get("imgLpv1").reason().contains("token concatenation"));
        assertFalse(decisions.get("other").readOnly());
    }

    @Test
    void imagesWithoutReferencesHaveNoShaderWrites() {
        var decision = MirrorCustomImageSharing.scan(
                List.of(new MirrorCustomImageSharing.Source("final", "void main() {}")), Set.of("unusedImage"))
                .get("unusedImage");

        assertTrue(decision.readOnly());
        assertEquals("no writes found", decision.reason());
    }

    @Test
    void keepsSourceOrderForFirstWriteDiagnostic() {
        var decision = MirrorCustomImageSharing.scan(List.of(
                new MirrorCustomImageSharing.Source("prepare", "imageStore(img, p, v);"),
                new MirrorCustomImageSharing.Source("composite", "img = 0;")), Set.of("img"))
                .get("img");

        assertTrue(decision.reason().contains("prepare"));
        assertTrue(decision.reason().contains("imageStore"));
    }
}
