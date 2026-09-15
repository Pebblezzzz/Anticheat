package dev.phantom.ac;

import java.io.Serializable;

public final class Maths {
  private Maths() {}
  public record Vec3(double x, double y, double z) implements Serializable {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
    public Vec3 multiply(double d) { return new Vec3(x * d, y * d, z * d); }
    public double horizontalDistanceSquared(Vec3 o) { double dx=x-o.x, dz=z-o.z; return dx*dx+dz*dz; }
  }
  public record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) implements Serializable {
    public Aabb move(Vec3 v) { return new Aabb(minX+v.x,minY+v.y,minZ+v.z,maxX+v.x,maxY+v.y,maxZ+v.z); }
    public boolean intersects(Aabb o) { return maxX > o.minX && minX < o.maxX && maxY > o.minY && minY < o.maxY && maxZ > o.minZ && minZ < o.maxZ; }
    public static Aabb playerAt(Vec3 p) { return new Aabb(p.x-.3,p.y,p.z-.3,p.x+.3,p.y+1.8,p.z+.3); }
  }
}
