package io.github.foundationgames.animatica.animation.bakery;

import com.google.common.collect.ImmutableList;
import io.github.foundationgames.animatica.Animatica;
import io.github.foundationgames.animatica.animation.AnimationMeta;
import io.github.foundationgames.animatica.util.TextureUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class AnimationBakery {
    public final Baking[] anims;
    private final NativeImage target;
    private int frame = 0;
    private final Deque<Identifier> frameIds = new ArrayDeque<>();
    private final Identifier targetTexId;

    public AnimationBakery(ResourceManager resources, Identifier targetTex, List<AnimationMeta> metas) throws IOException {
        this.anims = new Baking[metas.size()];
        for (int i = 0; i < metas.size(); i++) {
            this.anims[i] = new Baking(metas.get(i), resources);
        }

        try (var target = resources.getResource(targetTex).getInputStream()) {
            this.target = NativeImage.read(target);
        }

        this.targetTexId = targetTex;
    }

    public boolean hasNext() {
        for (var anim : anims) {
            if (!anim.isOnFrameZero()) {
                return true;
            }
        }
        return false;
    }

    public void advance() {
        var textures = MinecraftClient.getInstance().getTextureManager();

        boolean changed = frame <= 0;
        for (var anim : anims) {
            if (anim.isChanged()) {
                changed = true;
                break;
            }
        }

        if (changed) {
            var frameImg = new NativeImage(target.getFormat(), target.getWidth(), target.getHeight(), false);
            frameImg.copyFrom(target);

            Phase phase;
            for (var anim : anims) {
                phase = anim.getCurrentPhase();
                if (phase instanceof InterpolatedPhase iPhase) {
                    TextureUtil.blendCopy(anim.source, 0, iPhase.prevV, 0, iPhase.v, anim.width, anim.height, frameImg, anim.targetX, anim.targetY, iPhase.blend.getBlend(anim.getPhaseFrame()));
                } else {
                    TextureUtil.copy(anim.source, 0, phase.v, anim.width, anim.height, frameImg, anim.targetX, anim.targetY);
                }
            }

            var id = new Identifier(targetTexId.getNamespace(), targetTexId.getPath() + ".anim" + frameIds.size());
            textures.registerTexture(id, new NativeImageBackedTexture(frameImg));
            frameIds.push(id);
        } else {
            frameIds.push(frameIds.getFirst());
        }

        for (var anim : anims) anim.advance();
        frame++;
    }

    public Identifier[] bakeAndUpload() {
        int i = -1;
        do {
            advance();
            i++;
        } while (hasNext() && (Animatica.CONFIG.maxAnimFrames == null || i < Animatica.CONFIG.maxAnimFrames));

        var ids = new Identifier[frameIds.size()];
        frameIds.toArray(ids);
        return ids;
    }

    public static class Baking {
        private final List<Phase> phases;
        public final NativeImage source;
        public final int targetX;
        public final int targetY;
        public final int width;
        public final int height;
        private final int duration;

        private int frame = 0;
        private Phase currentPhase = null;
        private int phaseFrame = 0;
        private boolean changed = true;

        public Baking(AnimationMeta meta, ResourceManager resources) throws IOException {
            this.targetX = meta.targetX();
            this.targetY = meta.targetY();
            this.width = meta.width();
            this.height = meta.height();

            try (var source = resources.getResource(meta.source()).getInputStream()) {
                this.source = NativeImage.read(source);
            }

            var phaseBuilder = ImmutableList.<Phase>builder();
            int duration = 0;

            final int frames = (int)Math.floor((float)source.getHeight() / meta.height());

            int prevV = ((frames - 1) - meta.frameMapping().getOrDefault(frames - 1, frames - 1)) * meta.height(); // Initialize with the last frame in the animation
            for (int f = 0; f < frames; f++) {
                int fMap = meta.frameMapping().getOrDefault(f, f);
                int fDuration = meta.frameDurations().getOrDefault(fMap, meta.defaultFrameDuration());

                int v = ((frames - 1) - fMap) * meta.height(); // Reverses the frame number so that frames will be from top to bottom (rather than bottom to top)

                if (meta.interpolate()) {
                    // Handles adding interpolated animation phases
                    final int interpolatedDuration = fDuration - meta.interpolationDelay();
                    phaseBuilder.add(new InterpolatedPhase(interpolatedDuration, v, prevV, (phaseFrame) -> ((float) phaseFrame / interpolatedDuration)));
                    duration += interpolatedDuration;

                    if (meta.interpolationDelay() > 0) {
                        // Adds a static version of the current phase as a "delay" before the next interpolated phase (if specified in animation)
                        phaseBuilder.add(new Phase(meta.interpolationDelay(), v));
                        duration += meta.interpolationDelay();
                    }
                } else {
                    phaseBuilder.add(new Phase(fDuration, v));
                    duration += fDuration;
                }

                prevV = v;
            }

            this.duration = duration;
            this.phases = phaseBuilder.build();

            updateCurrentPhase();
        }

        public void updateCurrentPhase() {
            changed = false;
            int progress = frame;

            for (var phase : phases) {
                progress -= phase.duration; // Take away as much progress as each phase is long, until progress is below or equal to zero
                if (progress < 0) {
                    if (currentPhase != phase) {
                        // Marks baking anim as changed should it be in a new phase
                        changed = true;
                    }
                    if (phase instanceof InterpolatedPhase) changed = true; // Marks baking anim as changed should its current phase be a changing one

                    this.currentPhase = phase;
                    this.phaseFrame = phase.duration + progress; // Adding progress to the phase duration results in how far it is into the phase

                    return;
                }
            }
        }

        public Phase getCurrentPhase() {
            return currentPhase;
        }

        public int getPhaseFrame() {
            return phaseFrame;
        }

        public boolean isOnFrameZero() {
            return frame <= 0;
        }

        public boolean isChanged() {
            return changed;
        }

        public void advance() {
            frame++;
            if (frame >= duration) {
                frame = 0;
            }
            updateCurrentPhase();
        }
    }

    public static class Phase {
        public final int duration;
        public final int v;

        public Phase(int duration, int v) {
            this.duration = duration;
            this.v = v;
        }
    }

    public static class InterpolatedPhase extends Phase {
        public final int prevV;
        public final BlendInterpolator blend;

        public InterpolatedPhase(int duration, int v, int prevV, BlendInterpolator blend) {
            super(duration, v);
            this.prevV = prevV;
            this.blend = blend;
        }
    }

    @FunctionalInterface
    public interface BlendInterpolator {
        float getBlend(int phaseFrame);
    }
}
