package kr.co.addresslens

import org.junit.Assert.*
import org.junit.Test

class SelectionGeometryTest {
    @Test fun previewToRawBufferAccountsForAllFourRotationsAndNonzeroCrop() {
        val crop = ScanPixelBounds(100, 50, 1100, 550)
        val landscape = FloatBox(100f, 50f, 300f, 150f)
        val portrait = FloatBox(100f, 200f, 200f, 400f)
        assertEquals(ScanPixelBounds(200, 100, 400, 200), FrameGeometry.viewToBuffer(landscape,1000f,500f,crop,0))
        assertEquals(ScanPixelBounds(300, 350, 500, 450), FrameGeometry.viewToBuffer(portrait,500f,1000f,crop,90))
        assertEquals(ScanPixelBounds(800, 400, 1000, 500), FrameGeometry.viewToBuffer(landscape,1000f,500f,crop,180))
        assertEquals(ScanPixelBounds(700, 150, 900, 250), FrameGeometry.viewToBuffer(portrait,500f,1000f,crop,270))
    }
    @Test fun fillCenterCroppingIsIncludedAndOutsideRegionsAreNeverExpanded() {
        assertEquals(ScanPixelBounds(400, 700, 3600, 2300), FrameGeometry.viewToBuffer(
            FloatBox(100f,100f,900f,500f),1000f,600f,ScanPixelBounds(0,0,4000,3000),0))
        assertNull(FrameGeometry.viewToBuffer(FloatBox(2000f,0f,3000f,20f),1000f,500f,ScanPixelBounds(0,0,1000,500),0))
    }
    @Test fun pinchAndBoxMoveUseOriginalCoordinatesWithoutResamplingThePhoto() {
        val model = SelectionGeometry(4000,3000,1000f,750f)
        model.select(FloatBox(100f,150f,500f,450f))
        assertEquals(ScanPixelBounds(400,600,2000,1800), model.sourceBounds())
        model.zoom(2f,500f,375f)
        assertEquals(ScanPixelBounds(1200,1050,2000,1650), model.sourceBounds())
        model.move(100f,50f)
        assertEquals(ScanPixelBounds(1400,1150,2200,1750), model.sourceBounds())
        model.zoom(.5f,500f,375f)
        assertEquals(ScanPixelBounds(800,800,2400,2000), model.sourceBounds())
    }
    @Test fun movingResizingAndExtremeZoomStayInsideSource() {
        val model = SelectionGeometry(2000,3000,400f,300f)
        model.select(FloatBox(-999f,-999f,9000f,9000f))
        model.move(9000f,-9000f)
        model.zoom(1000f,200f,150f,9000f,-9000f)
        val bounds = model.sourceBounds()!!
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 2000 && bounds.bottom <= 3000)
        assertTrue(bounds.width > 0 && bounds.height > 0)
    }
    @Test fun cornerResizeKeepsOppositeCornerFixed() {
        val model = SelectionGeometry(2000,1000,1000f,500f)
        model.select(FloatBox(100f,100f,600f,400f))
        model.resize(0,900f,900f)
        assertEquals(600f, model.box.right)
        assertEquals(400f, model.box.bottom)
        assertEquals(32f, model.box.width)
        assertEquals(32f, model.box.height)
    }
}
