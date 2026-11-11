#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
uniform sampler2D PictureSampler;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

// The picture repeats this many times down the screen.
const float PICTURE_REPEATS = 2.0;

// The skin only decides where the model is. The colour comes from the picture behind the screen.
void main() {
    if (texture(Sampler0, texCoord0).a < 0.1) {
        discard;
    }
    vec2 screen = gl_FragCoord.xy / ScreenSize.y * PICTURE_REPEATS;
    vec4 picture = texture(PictureSampler, screen);
    fragColor = vec4(picture.rgb, 1.0) * vertexColor * ColorModulator;
}
