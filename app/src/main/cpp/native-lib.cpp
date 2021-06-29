#include <jni.h>
#include <string>
#include <iostream>
#include <sstream>

#include <gnuradio/logger.h>
#include <gnuradio/top_block.h>
#include <gnuradio/uhd/usrp_sink.h>
#include <gnuradio/analog/sig_source.h>
#include <gnuradio/blocks/file_source.h>
#include <gnuradio/blocks/multiply.h>
#include <gnuradio/blocks/null_sink.h>
#include <gnuradio/blocks/throttle.h>
#include <gnuradio/filter/rational_resampler_base.h>
#include <stdlib.h>

gr::top_block_sptr tb;

extern "C"
JNIEXPORT jobject JNICALL
Java_net_bastibl_txfile_MainActivity_fgInit(JNIEnv * env, jobject /*this*/, int fd, jstring usbfsPath) {

    setenv("VOLK_CONFIGPATH", getenv("EXTERNAL_STORAGE"), 1);
    setenv("GR_CONF_CONTROLPORT_ON", "true", 1);

    const char *usbfs_path = env->GetStringUTFChars(usbfsPath, NULL);

    tb = gr::make_top_block("txfile");

    std::stringstream args;
    args << "bbl=foo,type=b200,fd=" << fd << ",usbfs_path=" << usbfs_path;
    GR_INFO("fg", boost::str(boost::format("Using UHD args=%1%") % args.str()));

    ::uhd::stream_args_t stream_args;
    stream_args.cpu_format = "fc32";
    stream_args.otw_format = "sc16";

    double sample_rate = 100e6/100;
    int interpolation_factor = 20;
    int LO_offset = int(100e3);
    //double center_frequency = 500e6;

    std::vector<gr_complex> taps;

    //gr::uhd::usrp_sink::sptr snk = gr::uhd::usrp_sink::make(args.str(), stream_args);
    //snk->set_samp_rate(20e6);
    //snk->set_center_freq(uhd::tune_request_t(5.18e9, 0));
    //snk->set_normalized_gain(0.7);
    gr::filter::rational_resampler_base_ccc::sptr rational_resampler = gr::filter::rational_resampler_base_ccc::make(interpolation_factor, 1, taps);
    gr::blocks::throttle::sptr throttle = gr::blocks::throttle::make(sizeof(gr_complex), 1e6, true);
    gr::blocks::null_sink::sptr null_sink = gr::blocks::null_sink::make(sizeof(gr_complex));
    gr::blocks::multiply_cc::sptr multiply = gr::blocks::multiply_cc::make(1);
    gr::blocks::file_source::sptr file_source = gr::blocks::file_source::make(sizeof(gr_complex), "/home/basti/Downloads/ucssTxSig_UserID1_ModCod5_SF100_osf4.mat.bin", true, 0, 0);
    gr::analog::sig_source_c::sptr sig_source = gr::analog::sig_source_c::make(sample_rate, gr::analog::GR_COS_WAVE, LO_offset, .7, 0,0);

    tb->connect(file_source, 0, throttle, 0);
    tb->connect(throttle, 0, rational_resampler, 0);
    tb->connect(rational_resampler, 0, multiply, 0);

    tb->connect(sig_source, 0, multiply, 1);
    tb->connect(multiply, 0, null_sink, 0);

    GR_DEBUG("gnuradio", "constructed flowgraph");

    return nullptr;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_net_bastibl_txfile_MainActivity_fgStart(JNIEnv * env, jobject /*this*/, jstring tmpName) {

    nice(-200);
    const char *tmp_c;
    tmp_c = env->GetStringUTFChars(tmpName, NULL);
    setenv("TMP", tmp_c, 1);

    GR_DEBUG("gnuradio", "JNI starting flowgraph");
    tb->start();

    return nullptr;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_net_bastibl_txfile_MainActivity_fgStop(JNIEnv * env, jobject /*this*/) {
    tb->stop();
    tb->wait();

    return nullptr;
}