#version 330
#extension GL_ARB_separate_shader_objects : require

#include <offlineclient:outline.glsl>

uniform sampler2D MaskSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

bool covered(ivec2 texel) {
    return texelFetch(MaskSampler, texel, 0).a > 0.5;
}

// The nearest covered pixel along the row within reach. Red holds its offset from one hundred and twenty eight.
void main() {
    ivec2 size = textureSize(MaskSampler, 0);
    ivec2 here = min(ivec2(texCoord * vec2(size)), size - 1);
    int reach = clamp(Reach, 0, MOST_REACH);
    for (int step = 0; step <= reach; step++) {
        if (here.x + step < size.x && covered(ivec2(here.x + step, here.y))) {
            fragColor = vec4((128.0 + float(step)) / 255.0, 0.0, 0.0, 1.0);
            return;
        }
        if (here.x - step >= 0 && covered(ivec2(here.x - step, here.y))) {
            fragColor = vec4((128.0 - float(step)) / 255.0, 0.0, 0.0, 1.0);
            return;
        }
    }
    fragColor = vec4(0.0);
}
