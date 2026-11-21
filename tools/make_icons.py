from PIL import Image, ImageDraw
import math, os
os.chdir('/workspace/turan-android/app/src/main')
OLIVE=(58,74,40); OLIVE_D=(38,50,26); KHAKI=(201,184,130); GOLD=(222,186,72)
def star(d,cx,cy,r,fill):
    pts=[]
    for i in range(10):
        a=-math.pi/2+i*math.pi/5; rr=r if i%2==0 else r*0.42
        pts.append((cx+rr*math.cos(a),cy+rr*math.sin(a)))
    d.polygon(pts,fill=fill)
def shield_pts(cx,top,w,h,n=40):
    right=[(cx+(w/2)*math.cos(t*math.pi/2), top+h*0.42+(h*0.58)*math.sin(t*math.pi/2)) for t in [i/n for i in range(n+1)]]
    left=[(cx-(w/2)*math.cos(t*math.pi/2), top+h*0.42+(h*0.58)*math.sin(t*math.pi/2)) for t in [i/n for i in range(n,-1,-1)]]
    return [(cx-w/2,top),(cx+w/2,top)]+right+left
def chevron(d,cx,y,w,th,fill):
    d.polygon([(cx-w/2,y),(cx,y-w*0.36),(cx+w/2,y),(cx+w/2,y+th),(cx,y-w*0.36+th),(cx-w/2,y+th)],fill=fill)
def fg(S, mono=False):
    im=Image.new('RGBA',(S,S),(0,0,0,0)); d=ImageDraw.Draw(im); k=S/108.0
    cx=S/2; top=27*k; w=46*k; h=54*k
    W=(255,255,255,255)
    d.polygon(shield_pts(cx,top,w,h),fill=W if mono else KHAKI)
    d.polygon(shield_pts(cx,top+3*k,w-6*k,h-6.5*k),fill=(0,0,0,0) if mono else OLIVE_D)
    star(d,cx,top+13*k,7.5*k,W if mono else GOLD)
    for i in range(3): chevron(d,cx,top+30*k+i*6*k,22*k,3.4*k,W if mono else KHAKI)
    return im
dens={'mdpi':1,'hdpi':1.5,'xhdpi':2,'xxhdpi':3,'xxxhdpi':4}
for n,m in dens.items():
    S=int(108*m); os.makedirs(f'res/mipmap-{n}',exist_ok=True)
    fg(S*4).resize((S,S),Image.LANCZOS).save(f'res/mipmap-{n}/ic_launcher_foreground.png')
    fg(S*4,True).resize((S,S),Image.LANCZOS).save(f'res/mipmap-{n}/ic_launcher_monochrome.png')
    L=int(48*m); B=L*4
    f=fg(int(B*108/72)); off=(f.width-B)//2; crop=f.crop((off,off,off+B,off+B))
    leg=Image.new('RGBA',(B,B),(0,0,0,0)); ImageDraw.Draw(leg).rounded_rectangle([0,0,B-1,B-1],radius=int(B*0.22),fill=OLIVE); leg.alpha_composite(crop)
    leg.resize((L,L),Image.LANCZOS).save(f'res/mipmap-{n}/ic_launcher.png')
    rnd=Image.new('RGBA',(B,B),OLIVE+(255,)); rnd.alpha_composite(crop)
    mask=Image.new('L',(B,B),0); ImageDraw.Draw(mask).ellipse([0,0,B-1,B-1],fill=255)
    r2=Image.new('RGBA',(B,B),(0,0,0,0)); r2.paste(rnd,(0,0),mask); r2.resize((L,L),Image.LANCZOS).save(f'res/mipmap-{n}/ic_launcher_round.png')
    S2=int(24*m); B2=S2*4; im=Image.new('RGBA',(B2,B2),(0,0,0,0)); d=ImageDraw.Draw(im); k=B2/24
    d.polygon(shield_pts(B2/2,2*k,17*k,20*k),fill=(255,255,255,255)); star(d,B2/2,7.4*k,3.6*k,(0,0,0,0))
    for i in range(2): chevron(d,B2/2,14*k+i*3.2*k,9*k,1.7*k,(0,0,0,0))
    os.makedirs(f'res/drawable-{n}',exist_ok=True); im.resize((S2,S2),Image.LANCZOS).save(f'res/drawable-{n}/ic_stat.png')
P=512; f=fg(int(P*108/72)); off=(f.width-P)//2; crop=f.crop((off,off,off+P,off+P))
prev=Image.new('RGBA',(P,P),(0,0,0,0)); ImageDraw.Draw(prev).rounded_rectangle([0,0,P-1,P-1],radius=110,fill=OLIVE); prev.alpha_composite(crop)
prev.save('/workspace/turan-asistan-icon.png')
Wd='/workspace/emir-eri-turan/icons'
full=Image.new('RGBA',(P,P),OLIVE+(255,)); full.alpha_composite(crop); full=full.convert('RGB')
full.save(f'{Wd}/icon-512.png'); full.resize((192,192),Image.LANCZOS).save(f'{Wd}/icon-192.png'); full.resize((180,180),Image.LANCZOS).save(f'{Wd}/apple-touch-icon.png')
mk=Image.new('RGBA',(P,P),OLIVE+(255,)); mk.alpha_composite(fg(P)); mk.convert('RGB').save(f'{Wd}/icon-maskable-512.png')
