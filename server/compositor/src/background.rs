//! Background gradient renderer.
//!
//! Draws a fullscreen vertical gradient from black at the top to dark turquoise
//! at the bottom. Uses a custom Smithay texture shader that computes the
//! gradient from texture coordinates, applied to a 1x1 dummy texture rendered
//! fullscreen.

use log::{error, info};

use smithay::backend::renderer::gles::{
    GlesFrame, GlesRenderer, GlesTexProgram, GlesTexture,
    Uniform, UniformName, UniformType, UniformValue,
};
use smithay::utils::{Point, Rectangle, Size, Transform};

use crate::gl_import::AhbTextureImporter;

/// Dark turquoise target color at the bottom of the screen (R, G, B).
const TURQUOISE: (f32, f32, f32) = (0.1, 0.75, 0.95);

/// Compiled gradient shader and dummy texture for background rendering.
pub struct BackgroundRenderer {
    shader: GlesTexProgram,
    texture: GlesTexture,
}

impl BackgroundRenderer {
    /// Compile the gradient shader and create a 1x1 dummy texture.
    /// Must be called with EGL context current.
    pub fn new(renderer: &mut GlesRenderer, importer: &AhbTextureImporter) -> Option<Self> {
        let shader = compile_gradient_shader(renderer)?;
        let texture = importer.create_dummy_texture_2d(renderer)?;
        Some(BackgroundRenderer { shader, texture })
    }

    /// Draw the fullscreen background gradient.
    pub fn draw(
        &self,
        frame: &mut GlesFrame<'_, '_>,
        width: i32,
        height: i32,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let src = Rectangle::from_size(Size::<i32, smithay::utils::Buffer>::from((1, 1)).to_f64());
        let dst = Rectangle::new(Point::from((0, 0)), Size::from((width, height)));
        let damage = Rectangle::from_size(Size::from((width, height)));

        frame.render_texture_from_to(
            &self.texture,
            src,
            dst,
            &[damage],
            &[],
            Transform::Normal,
            1.0,
            Some(&self.shader),
            &[
                Uniform::new("target_r", UniformValue::_1f(TURQUOISE.0)),
                Uniform::new("target_g", UniformValue::_1f(TURQUOISE.1)),
                Uniform::new("target_b", UniformValue::_1f(TURQUOISE.2)),
            ],
        )?;
        Ok(())
    }
}

/// Compile the gradient fragment shader.
fn compile_gradient_shader(renderer: &mut GlesRenderer) -> Option<GlesTexProgram> {
    match renderer.compile_custom_texture_shader(
        r#"
#version 100

//_DEFINES_

#if defined(EXTERNAL)
#extension GL_OES_EGL_image_external : require
#endif

precision mediump float;
#if defined(EXTERNAL)
uniform samplerExternalOES tex;
#else
uniform sampler2D tex;
#endif

uniform float alpha;
varying vec2 v_coords;

uniform float target_r;
uniform float target_g;
uniform float target_b;

#if defined(DEBUG_FLAGS)
uniform float tint;
#endif

void main() {
    // v_coords: (0,0) at top-left, (1,1) at bottom-right in screen space.
    // Gradient: black at top, turquoise at bottom.
    float t = 1.0 - v_coords.y;
    gl_FragColor = vec4(target_r * t, target_g * t, target_b * t, 1.0);
}
"#,
        &[
            UniformName::new("target_r", UniformType::_1f),
            UniformName::new("target_g", UniformType::_1f),
            UniformName::new("target_b", UniformType::_1f),
        ],
    ) {
        Ok(program) => {
            info!("Background gradient shader compiled");
            Some(program)
        }
        Err(e) => {
            error!("Failed to compile background gradient shader: {:?}", e);
            None
        }
    }
}

