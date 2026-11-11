#version 330
#extension GL_ARB_separate_shader_objects : require

#include <offlineclient:outline.glsl>

uniform sampler2D MaskSampler;
uniform sampler2D RowsSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Full strength across the line and a soft fall over the glow past it.
float edge_strength(float distance) {
    float line = float(LineWidth) + 0.5;
    if (distance < line) {
        return 1.0;
    }
    if (GlowWidth <= 0) {
        return 0.0;
    }
    float past = (distance - line) / float(GlowWidth);
    return past >= 1.0 ? 0.0 : (1.0 - past) * (1.0 - past);
}

// The rows pass found the nearest covered pixel of every row. The nearest of those over the column is the
// nearest covered pixel of all and its number gives the colour.
void main() {
    ivec2 size = textureSize(MaskSampler, 0);
    ivec2 here = min(ivec2(texCoord * vec2(size)), size - 1);
    int reach = clamp(Reach, 0, MOST_REACH);
    int best = (MOST_REACH + 1) * (MOST_REACH + 1) * 2;
    ivec2 nearest = ivec2(-1);
    for (int step = 0; step <= reach && step * step < best; step++) {
        for (int turn = 0; turn < 2; turn++) {
            int dy = turn == 0 ? step : -step;
            int y = here.y + dy;
            if ((turn == 1 && step == 0) || y < 0 || y >= size.y) {
                continue;
            }
            vec4 row = texelFetch(RowsSampler, ivec2(here.x, y), 0);
            if (row.a < 0.5) {
                continue;
            }
            int dx = int(row.r * 255.0 + 0.5) - 128;
            int distance = dx * dx + dy * dy;
            if (distance < best) {
                best = distance;
                nearest = ivec2(here.x + dx, y);
            }
        }
    }
    if (nearest.x < 0) {
        discard;
    }
    vec4 color = outline_color(outline_id(texelFetch(MaskSampler, nearest, 0)));
    float strength = best == 0 ? FillAlpha : edge_strength(sqrt(float(best)));
    if (strength <= 0.0) {
        discard;
    }
    fragColor = vec4(color.rgb, color.a * strength);
}
