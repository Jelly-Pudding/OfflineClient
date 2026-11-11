#ifndef OFFLINECLIENT_OUTLINE_GLSL
#define OFFLINECLIENT_OUTLINE_GLSL

#define MOST_REACH 32
#define PALETTE_QUADS 512

layout(std140) uniform OutlineConfig {
    int Reach;
    int LineWidth;
    int GlowWidth;
    float FillAlpha;
    ivec4 Palette[PALETTE_QUADS];
};

// Each silhouette in the mask is drawn in its number with the high byte in green and the low byte in blue.
int outline_id(vec4 mask) {
    return int(mask.g * 255.0 + 0.5) * 256 + int(mask.b * 255.0 + 0.5);
}

// The palette holds four packed ARGB colours in each slot.
vec4 outline_color(int id) {
    int index = clamp(id, 0, PALETTE_QUADS * 4 - 1);
    ivec4 quad = Palette[index / 4];
    int lane = index - (index / 4) * 4;
    int packed = lane == 0 ? quad.x : (lane == 1 ? quad.y : (lane == 2 ? quad.z : quad.w));
    return vec4(float((packed >> 16) & 255), float((packed >> 8) & 255), float(packed & 255),
        float((packed >> 24) & 255)) / 255.0;
}

#endif
