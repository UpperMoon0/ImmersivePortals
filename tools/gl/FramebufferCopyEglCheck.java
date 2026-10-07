/** Optional live-GL check of the real production helper; no Minecraft client is launched.
 * The real RenderTarget carrier is allocated without its game constructor solely to set
 * existing attachment IDs. No production helper code or GL calls are mocked.
 * Run through tools/verify_framebuffer_copy_gl.py after the normal project build.
 */
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.*;
import org.lwjgl.opengl.*;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import qouteall.imm_ptl.core.compat.iris_compatibility.IPIrisHelper;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
public class FramebufferCopyEglCheck {
 public static void main(String[] args) throws Exception {
  System.out.println("EGL version="+EGL10.eglQueryString(0,0x3054));
  long display=org.lwjgl.system.JNI.callPPP(0x31DD,0L,0L,EGL10.eglGetProcAddress("eglGetPlatformDisplayEXT"));
  int[] major=new int[1],minor=new int[1];
  if(!EGL10.eglInitialize(display,major,minor)) throw new AssertionError("eglInitialize "+EGL10.eglGetError());
  EGL.createDisplayCapabilities(display,major[0],minor[0]);
  if(!EGL12.eglBindAPI(0x30A2)) throw new AssertionError("eglBindAPI");
  PointerBuffer configs=BufferUtils.createPointerBuffer(1);int[] count=new int[1];
  if(!EGL10.eglChooseConfig(display,new int[]{0x3033,1,0x3040,8,0x3024,8,0x3023,8,0x3022,8,0x3038},configs,count)) throw new AssertionError("eglChooseConfig");
  long surface=EGL10.eglCreatePbufferSurface(display,configs.get(0),new int[]{0x3057,1,0x3056,1,0x3038});
  String[] version=System.getProperty("ip.test.glVersion","4.5").split("\\.");
  long context=EGL10.eglCreateContext(display,configs.get(0),0,new int[]{0x3098,Integer.parseInt(version[0]),0x30FB,Integer.parseInt(version[1]),0x30FD,1,0x3038});
  if(!EGL10.eglMakeCurrent(display,surface,surface,context)) throw new AssertionError("eglMakeCurrent "+EGL10.eglGetError());
  GL.createCapabilities();
  System.out.println(GL11.glGetString(GL11.GL_VERSION));
  System.out.println(GL11.glGetString(GL11.GL_RENDERER));
  System.out.println("Capabilities: "+IPIrisHelper.describeCopyCapabilities());
  if(Boolean.getBoolean("ip.test.requireNativeBlit")) {
   check(!GL.getCapabilities().OpenGL43&&!GL.getCapabilities().GL_ARB_copy_image,"copy-image capability really absent");
   check(!IPIrisHelper.isCopyImageSubDataSupported(),"native fallback selected without force flag");
  }
  int cases=0;
  for(int format:new int[]{GL_DEPTH24_STENCIL8,GL_DEPTH32F_STENCIL8}) {
   for(int size:new int[]{8,13}) {
    RenderTarget from=target(size,format), to=target(size,GL_DEPTH24_STENCIL8);
    IPIrisHelper.matchDepthAttachment(from,to);
    for(boolean blit:new boolean[]{false,true}) {
     System.setProperty("ip.iris.forceFramebufferBlit",Boolean.toString(blit));
     seed(from,.375f,77); seed(to,.875f,23);
     int expectedRead=to.frameBufferId,expectedDraw=from.frameBufferId;
     glBindFramebuffer(GL_READ_FRAMEBUFFER,expectedRead); glBindFramebuffer(GL_DRAW_FRAMEBUFFER,expectedDraw);
     glBindTexture(GL_TEXTURE_2D,from.getColorTextureId());
     glEnable(GL_SCISSOR_TEST); glScissor(0,0,0,0); glEnable(GL_FRAMEBUFFER_SRGB);
     glDepthMask(false); glStencilMask(0); glColorMask(false,false,false,false);
     IPIrisHelper.newCopyDepthStencil(from,to); IPIrisHelper.copyColor(from,to);
     check(glGetInteger(GL_READ_FRAMEBUFFER_BINDING)==expectedRead,"read binding");
     check(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)==expectedDraw,"draw binding");
     check(glGetInteger(GL_TEXTURE_BINDING_2D)==from.getColorTextureId(),"texture binding");
     check(glIsEnabled(GL_SCISSOR_TEST)&&glIsEnabled(GL_FRAMEBUFFER_SRGB),"enable state");
     check(!glGetBoolean(GL_DEPTH_WRITEMASK)&&glGetInteger(GL_STENCIL_WRITEMASK)==0,"write masks");
     sample(to,.375f,77);
     float[] rgba=new float[4]; glReadPixels(2,2,1,1,GL_RGBA,GL_FLOAT,rgba);
     check(Math.abs(rgba[0]-.25f)<.006f&&Math.abs(rgba[1]-.75f)<.006f,"color copied");
     seed(to,.875f,23); glEnable(GL_SCISSOR_TEST);
     IPIrisHelper.copyDepthStencil(from,to,true,false); sample(to,.375f,23);
     seed(to,.875f,23); glEnable(GL_SCISSOR_TEST);
     IPIrisHelper.copyDepthStencil(from,to,false,true); sample(to,.875f,77);
     check(glGetError()==GL_NO_ERROR,"GL error format="+format+" blit="+blit);
     System.out.println("PASS format="+format+" size="+size+" forcedBlit="+blit+" path="+(IPIrisHelper.isCopyImageSubDataSupported()?"copy-image":"framebuffer-blit")+" full+partial copies/state");
     cases++;
    }
    destroy(from); destroy(to);
   }
  }
  System.out.println("PASS actual IPIrisHelper: "+cases+" cases");
  if(args.length>0 && args[0].equals("--clipping-state")) checkClippingState();
  EGL10.eglMakeCurrent(display,0,0,0);
  EGL10.eglDestroyContext(display,context);
  EGL10.eglDestroySurface(display,surface);
  EGL10.eglTerminate(display);
 }

 private static void checkClippingState() throws Exception {
  // Load the actual compiled class only when its full runtime classpath was supplied.
  Class<?> front=Class.forName("qouteall.imm_ptl.core.render.FrontClipping");
  var enabled=front.getField("isClippingEnabled");
  var before=front.getDeclaredField("activeClipPlaneEquationBeforeModelView");before.setAccessible(true);
  var after=front.getDeclaredField("activeClipPlaneAfterModelView");after.setAccessible(true);
  var suspend=front.getMethod("suspendClipping");
  var restore=front.getMethod("restoreClippingState",Class.forName("qouteall.imm_ptl.core.render.FrontClipping$ClippingState"));
  for(boolean logical:new boolean[]{false,true}) for(boolean gl:new boolean[]{false,true}) {
   double[] beforePlane={1,2,3,4},afterPlane={5,6,7,8};
   before.set(null,beforePlane);after.set(null,afterPlane);
   enabled.setBoolean(null,logical);
   if(gl)glEnable(GL_CLIP_DISTANCE0);else glDisable(GL_CLIP_DISTANCE0);
   Object state=suspend.invoke(null);
   check(!enabled.getBoolean(null)&&!glIsEnabled(GL_CLIP_DISTANCE0),"clipping suspended");
   Object nested=suspend.invoke(null);
   restore.invoke(null,nested);
   check(!enabled.getBoolean(null)&&!glIsEnabled(GL_CLIP_DISTANCE0),"nested clipping suspension");
   before.set(null,new double[4]);after.set(null,new double[4]);
   restore.invoke(null,state);
   check(enabled.getBoolean(null)==logical&&glIsEnabled(GL_CLIP_DISTANCE0)==gl,"clipping restored");
   check(before.get(null)==beforePlane&&after.get(null)==afterPlane,"clip equations restored");
   System.out.println("PASS FrontClipping logical="+logical+" GL="+gl+" nested+planes");
  }
 }
 private static RenderTarget target(int size,int format)throws Exception {
  // Allocate the actual Minecraft carrier without initializing its game-only constructor.
  var uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);
  var unsafe=(sun.misc.Unsafe)uf.get(null);
  RenderTarget t=(RenderTarget)unsafe.allocateInstance(TextureTarget.class);
  t.width=t.height=size;t.frameBufferId=glGenFramebuffers();
  glBindFramebuffer(GL_FRAMEBUFFER,t.frameBufferId);
  int color=texture(size,GL_RGBA8,GL_RGBA,GL_UNSIGNED_BYTE);
  glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
  int depth=texture(size,format,GL_DEPTH_STENCIL,format==GL_DEPTH32F_STENCIL8?GL_FLOAT_32_UNSIGNED_INT_24_8_REV:GL_UNSIGNED_INT_24_8);
  glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_STENCIL_ATTACHMENT,GL_TEXTURE_2D,depth,0);
  var cf=RenderTarget.class.getDeclaredField("colorTextureId");cf.setAccessible(true);cf.set(t,color);
  var df=RenderTarget.class.getDeclaredField("depthBufferId");df.setAccessible(true);df.set(t,depth);
  check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"complete FBO");return t;
 }
 private static int texture(int size,int format,int pixelFormat,int type) {
  int texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
  glTexImage2D(GL_TEXTURE_2D,0,format,size,size,0,pixelFormat,type,0L);return texture;
 }
 private static void seed(RenderTarget t,float depth,int stencil) {
  glDisable(GL_SCISSOR_TEST);glDisable(GL_FRAMEBUFFER_SRGB);
  glDepthMask(true);glStencilMask(~0);glColorMask(true,true,true,true);
  glBindFramebuffer(GL_FRAMEBUFFER,t.frameBufferId);
  glClearBufferfv(GL_COLOR,0,new float[]{.25f,.75f,.5f,1});glClearBufferfi(GL_DEPTH_STENCIL,0,depth,stencil);
 }
 private static void sample(RenderTarget t,float expectedDepth,int expectedStencil) {
  glBindFramebuffer(GL_READ_FRAMEBUFFER,t.frameBufferId);
  float[] depth=new float[1];int[] stencil=new int[1];
  glReadPixels(2,2,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,depth);glReadPixels(2,2,1,1,GL_STENCIL_INDEX,GL_UNSIGNED_INT,stencil);
  check(Math.abs(depth[0]-expectedDepth)<.00001f,"depth expected="+expectedDepth+" actual="+depth[0]);
  check(stencil[0]==expectedStencil,"stencil expected="+expectedStencil+" actual="+stencil[0]);
 }
 private static void destroy(RenderTarget t) {glDeleteTextures(t.getColorTextureId());glDeleteTextures(t.getDepthTextureId());glDeleteFramebuffers(t.frameBufferId);}
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
